// taskmanagerd — privileged system monitor daemon for Task Manager.
//
// Runs as a standalone executable (packaged as libtaskmanagerd.so so Android
// extracts it into nativeLibraryDir) started via root `su -c` or a Shizuku
// shell. Reads kernel interfaces directly (/proc, /sys) and speaks
// newline-delimited JSON on stdin/stdout.
//
// Protocol v2:
//   - On startup the daemon immediately announces itself:
//       {"type":"HELLO","proto":2,"version":"...","caps":[...]}
//     The app validates `proto` before using the daemon (version handshake,
//     so daemon and app can evolve independently).
//   - Every request may carry an "id" field; responses echo it back so the
//     app can correlate responses with requests regardless of ordering.
//   - CPU_PING is served from a background sampler thread: it never blocks
//     the command loop, and the window is a fixed 100ms rather than the gap
//     between two successive polls.
//   - Per-process CPU usage is windowed (delta of utime+stime between
//     successive samples). The first time a pid is seen, the lifetime
//     average is reported as a fallback (same as protocol v1 behaviour).
//   - KILL_GRACEFUL sends SIGTERM and escalates to SIGKILL in the background
//     if the process is still alive after the grace period (default 3000ms).
//     The response reports signal delivery immediately (never blocks the
//     command loop); the escalation worker emits no further messages.
//   - CORE_PING reports per-core usage + frequencies. BATTERY_PING reads
//     /sys/class/power_supply with vendor unit heuristics. PSS_PING reads
//     /proc/<pid>/smaps_rollup. SUBSCRIBE/UNSUBSCRIBE push selected topics
//     (cpu/battery/mem/net) on a timer from a dedicated thread.
//   - Unknown commands and malformed JSON answer {"type":"ERROR",...}
//     instead of staying silent, so clients can fail fast.

#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <climits>
#include <pwd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <condition_variable>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include <dirent.h>

#include "json.hpp"

namespace fs = std::filesystem;
using json = nlohmann::json;

static const char* DAEMON_NAME = "taskmanagerd";
static const char* DAEMON_VERSION = "1.7.0-fork3";
static constexpr int PROTOCOL_VERSION = 2;

// Capabilities advertised in HELLO. The app can degrade gracefully when a
// capability is missing (older daemon) or use extra ones (newer daemon).
static json daemonCaps() {
    return json::array({
        "request_id",           // responses echo the request "id"
        "windowed_proc_cpu",    // per-process CPU is a windowed delta, not lifetime average
        "nonblocking_cpu_ping", // CPU_PING answered from a sampler thread
        "proc_cpu_time",        // processes expose raw cpuTimeTicks
        "kill_graceful",        // KILL_GRACEFUL: SIGTERM + background SIGKILL escalation
        "core_ping",            // CORE_PING: per-core usage + frequencies
        "battery_ping",         // BATTERY_PING: battery stats with unit heuristics
        "pss_ping",             // PSS_PING: PSS via /proc/<pid>/smaps_rollup
        "push_subscribe",       // SUBSCRIBE/UNSUBSCRIBE push mode
        "freeze",               // FREEZE/THAW: SIGSTOP/SIGCONT a pid
        "app_power",            // APP_POWER: per-uid mAh + wakelocks from batterystats
        "reaper",               // HELLO reports orphaned sibling daemons it killed
        "current_raw",          // BATTERY_STATS carries the unscaled currentRaw
    });
}

static int g_reapedCount();

static json helloMessage() {
    return {
        {"type", "HELLO"},
        {"proto", PROTOCOL_VERSION},
        {"daemon", DAEMON_NAME},
        {"version", DAEMON_VERSION},
        {"caps", daemonCaps()},
        {"reaped", g_reapedCount()},
    };
}

static std::string toLower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(),
                   [](unsigned char c){ return std::tolower(c); });
    return s;
}

static bool isAllDigits(const std::string& s) {
    if (s.empty()) return false;
    for (unsigned char c : s) {
        if (!std::isdigit(c)) return false;
    }
    return true;
}

// Package names are interpolated into a shell command for FORCE_STOP, so the
// charset is restricted to a safe subset (no whitespace, no metacharacters).
static bool isValidPackageName(const std::string& pkg) {
    if (pkg.empty() || pkg.size() > 255) return false;
    for (unsigned char c : pkg) {
        if (!(std::isalnum(c) || c == '.' || c == '_')) return false;
    }
    return true;
}

static bool isCpuThermalType(const std::string& type) {
    std::string t = toLower(type);
    return (t.find("cpu") != std::string::npos) ||
           (t.find("soc") != std::string::npos) ||
           (t.find("ap")  != std::string::npos) ||
           (t.find("cluster") != std::string::npos);
}

// CPU temperature = hottest CPU-ish thermal zone. Phones expose a lot of
// zones (108 on a Snapdragon 8 Gen 3) and their "type" never changes, so
// the list of matching temp paths is built once and refreshed every 5 min
// instead of re-reading every type file per call. The result itself is
// cached for 1 s: push frames and CTEMP_PING both ask for it.
static std::mutex g_thermalMutex;
static std::vector<std::string> g_cpuZoneTempPaths;
static std::chrono::steady_clock::time_point g_cpuZonesScannedAt{};
static bool g_cpuZonesScanned = false;
static int g_cpuTempCached = -1;
static std::chrono::steady_clock::time_point g_cpuTempAt{};

static void rescanCpuZones() {
    g_cpuZoneTempPaths.clear();
    const std::string basePath = "/sys/class/thermal/";
    DIR* dir = opendir(basePath.c_str());
    if (!dir) return;
    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        std::string name = entry->d_name;
        if (name.find("thermal_zone") == std::string::npos) continue;
        std::string zonePath = basePath + name;
        std::ifstream typeFile(zonePath + "/type");
        if (!typeFile.is_open()) continue;
        std::string type;
        std::getline(typeFile, type);
        if (isCpuThermalType(type)) g_cpuZoneTempPaths.push_back(zonePath + "/temp");
    }
    closedir(dir);
}

int getCpuTemperatureCelsius() {
    std::lock_guard<std::mutex> lock(g_thermalMutex);
    auto now = std::chrono::steady_clock::now();
    if (g_cpuZonesScanned && now - g_cpuTempAt < std::chrono::seconds(1)) return g_cpuTempCached;
    if (!g_cpuZonesScanned || now - g_cpuZonesScannedAt > std::chrono::minutes(5)) {
        rescanCpuZones();
        g_cpuZonesScanned = true;
        g_cpuZonesScannedAt = now;
    }
    int maxTemp = -1;
    for (const auto& path : g_cpuZoneTempPaths) {
        char buf[32];
        int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n <= 0) continue;
        buf[n] = '\0';
        long raw = atol(buf);
        if (raw <= 0) continue;
        int tempC = (raw > 1000) ? static_cast<int>(raw / 1000) : static_cast<int>(raw);
        if (tempC >= 5 && tempC <= 100) maxTemp = std::max(maxTemp, tempC);
    }
    g_cpuTempCached = maxTemp;
    g_cpuTempAt = now;
    return maxTemp;
}

std::optional<int> getBatteryCycleCount() {
    static const std::vector<std::string> paths = {
            "/sys/class/power_supply/battery/cycle_count",
            "/sys/class/power_supply/bms/cycle_count",
            "/sys/class/power_supply/Battery/cycle_count",
    };

    for (const auto& path : paths) {
        std::ifstream file(path);
        if (!file.is_open()) continue;

        std::string content;
        std::getline(file, content);

        try {
            return std::stoi(content);
        } catch (...) {
            continue;
        }
    }

    return std::nullopt;
}

std::vector<int> listPids() {
    std::vector<int> pids;
    pids.reserve(256);
    for (const auto &entry : fs::directory_iterator("/proc")) {
        try {
            if (entry.is_directory() && isAllDigits(entry.path().filename().string())) {
                pids.push_back(std::stoi(entry.path().filename().string()));
            }
        } catch (...) {}
    }
    return pids;
}

static std::atomic<bool> keep_running{true};

void handle_sigint(int) {
    keep_running.store(false);
}

std::string now_str() {
    time_t t = time(nullptr);
    struct tm tm{};
    localtime_r(&t, &tm);
    char buf[64];
    strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S", &tm);
    return {buf};
}

void log_line(const std::string &line) {
    std::string msg = "[" + now_str() + "] " + line + "\n";
    write(STDERR_FILENO, msg.c_str(), msg.size());
}

// All stdout writes go through this mutex so responses are never interleaved,
// even once additional threads emit messages (sampler/push in later phases).
static std::mutex g_write_mutex;

bool send_msg(const std::string &msg) {
    std::string data = msg + "\n";
    std::lock_guard<std::mutex> lock(g_write_mutex);
    size_t total = 0;
    while (total < data.size()) {
        ssize_t written = write(STDOUT_FILENO, data.data() + total, data.size() - total);
        if (written <= 0) return false;
        total += written;
    }
    return true;
}

bool send_json(const json &j) {
    return send_msg(j.dump());
}

// ---------------------------------------------------------------------------
// System-wide CPU sampler: a background thread keeps a fresh 100ms-window
// usage value in an atomic. Commands read it without ever blocking.
// ---------------------------------------------------------------------------

struct CpuStat {
    long user = 0, nice = 0, system = 0, idle = 0, iowait = 0, irq = 0, softirq = 0, steal = 0;
    long total() const { return user + nice + system + idle + iowait + irq + softirq + steal; }
    long active() const { return total() - idle; }
};

// Parses the 8 counter fields after a "cpu" label. Returns false if fewer
// than 8 fields were present.
static bool parseCpuFields(const std::string& line, CpuStat& out) {
    std::istringstream iss(line);
    std::string cpuLabel;
    long v[8] = {0};
    iss >> cpuLabel;
    for (int i = 0; i < 8; ++i) if (!(iss >> v[i])) return false;
    out = {v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]};
    return true;
}

CpuStat readCpuStat() {
    std::ifstream file("/proc/stat");
    if (!file.is_open()) return {};
    std::string line;
    std::getline(file, line);
    CpuStat out;
    if (line.rfind("cpu ", 0) == 0 && parseCpuFields(line, out)) return out;
    return {};
}

static std::atomic<int> g_cpuUsage{-1}; // -1 = no sample yet

// Per-core usage (0..100), maintained by the sampler on the same 100ms
// window as the aggregate. Static storage zero-initializes the atomics.
static constexpr int MAX_CORES = 64;
static std::atomic<int> g_coreUsage[MAX_CORES];
static std::atomic<int> g_coreCount{0};

// Sampling period of the CPU sampler. Every open of /proc/stat makes the
// kernel render the whole file (IRQ table included, ~10 ms on an 8-core
// phone), so the sampler runs at 1 Hz by default -- a 1 s window is plenty
// for CPU_PING / CORE_PING and the widget -- and only speeds up (down to
// 250 ms) while a push subscription with the "cpu" topic asks for it.
static constexpr int SAMPLE_PERIOD_IDLE_MS = 1000;
static std::atomic<int> g_samplePeriodMs{SAMPLE_PERIOD_IDLE_MS};

// One pass over /proc/stat: the aggregate "cpu " line plus every "cpuN"
// line. Stops at the first non-cpu line, so the very long "intr" line that
// follows is never read (it dominated the sampler's cost when the file was
// read twice per tick in full).
static bool readAllCpuStat(CpuStat& total, std::vector<CpuStat>& cores) {
    std::ifstream file("/proc/stat");
    if (!file.is_open()) return false;
    cores.clear();
    std::string line;
    bool gotTotal = false;
    while (std::getline(file, line)) {
        if (line.rfind("cpu", 0) != 0) break;
        if (line.size() > 3 && line[3] == ' ') {
            gotTotal = parseCpuFields(line, total);
        } else if (line.size() > 3 && std::isdigit(static_cast<unsigned char>(line[3]))) {
            CpuStat c;
            if (parseCpuFields(line, c)) cores.push_back(c);
        }
    }
    return gotTotal;
}

void cpuSamplerLoop() {
    CpuStat prev, curr;
    std::vector<CpuStat> prevCores;
    std::vector<CpuStat> currCores;
    readAllCpuStat(prev, prevCores);
    g_coreCount.store((int)prevCores.size());
    // First window is short so CPU_PING right after the handshake already
    // has a value; afterwards the period follows g_samplePeriodMs.
    bool first = true;
    while (keep_running.load()) {
        // Sleep in 50 ms slices so period changes / shutdown apply quickly.
        int waited = 0;
        while (keep_running.load() && waited < (first ? 200 : g_samplePeriodMs.load())) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            waited += 50;
        }
        first = false;
        if (!keep_running.load()) break;
        if (!readAllCpuStat(curr, currCores)) continue;

        long totalDiff = curr.total() - prev.total();
        long activeDiff = curr.active() - prev.active();
        if (totalDiff > 0) {
            double usage = (double)activeDiff / (double)totalDiff * 100.0;
            g_cpuUsage.store(std::clamp((int)usage, 0, 100));
        }
        prev = curr;

        size_t n = std::min(prevCores.size(), currCores.size());
        for (size_t i = 0; i < n && i < MAX_CORES; ++i) {
            long dTotal = currCores[i].total() - prevCores[i].total();
            long dActive = currCores[i].active() - prevCores[i].active();
            if (dTotal > 0) {
                double u = (double)dActive / (double)dTotal * 100.0;
                g_coreUsage[i].store(std::clamp((int)u, 0, 100));
            }
        }
        g_coreCount.store((int)currCores.size());
        prevCores.swap(currCores);
    }
}

// ---------------------------------------------------------------------------
// Windowed per-process CPU: keep the last (utime+stime) tick count and sample
// timestamp per pid. Usage = delta ticks / (dt * CLK_TCK) * 100. The first
// sample for a pid returns -1 so the caller can fall back to the lifetime
// average. Stale entries are pruned on every LIST_PROCESS.
// ---------------------------------------------------------------------------

struct ProcCpuSample {
    long totalTicks;
    std::chrono::steady_clock::time_point ts;
};

static std::mutex g_procCpuMutex;
static std::unordered_map<int, ProcCpuSample> g_procCpuSamples;

static float windowedProcCpuUsage(int pid, long totalTicks) {
    std::lock_guard<std::mutex> lock(g_procCpuMutex);
    auto now = std::chrono::steady_clock::now();
    auto it = g_procCpuSamples.find(pid);
    if (it == g_procCpuSamples.end()) {
        g_procCpuSamples.emplace(pid, ProcCpuSample{totalTicks, now});
        return -1.0f;
    }
    auto &prev = it->second;
    double dt = std::chrono::duration<double>(now - prev.ts).count();
    long dTicks = totalTicks - prev.totalTicks;
    prev.totalTicks = totalTicks;
    prev.ts = now;
    if (dt <= 0.0 || dTicks < 0) return -1.0f;
    double hz = (double)sysconf(_SC_CLK_TCK);
    double usage = (dTicks / hz) / dt * 100.0;
    return (float)std::max(0.0, usage); // may exceed 100 on multi-core, like top(1)
}

static void pruneProcCpuSamples(const std::vector<int>& livePids) {
    std::unordered_set<int> live(livePids.begin(), livePids.end());
    std::lock_guard<std::mutex> lock(g_procCpuMutex);
    for (auto it = g_procCpuSamples.begin(); it != g_procCpuSamples.end();) {
        if (live.count(it->first) == 0) it = g_procCpuSamples.erase(it);
        else ++it;
    }
}

// ---------------------------------------------------------------------------
// Graceful kill: deliver SIGTERM now, escalate to SIGKILL in the background
// if the process outlives the grace period. The worker is detached and must
// therefore only use raw syscalls (no iostream/globals) so it is safe even
// if it is still sleeping when the daemon exits. Identity of the victim is
// tracked via its /proc/<pid>/stat starttime so a recycled pid is never
// signalled by mistake.
// ---------------------------------------------------------------------------

// Reads field 22 (starttime) of /proc/<pid>/stat with plain open/read.
// Returns -1 when the process is gone or the file is unreadable.
static long readStarttimeRaw(int pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    char buf[512];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    char* p = strrchr(buf, ')');
    if (!p || p[1] != ' ') return -1;
    // Tokens after ") " start at stat field 3 (state), so token N is field
    // N+2: ppid(2) pgrp(3) ... itrealvalue(19) starttime(20 == field 22).
    long starttime = -1;
    int tok = 0;
    char* save = nullptr;
    for (char* t = strtok_r(p + 2, " ", &save); t != nullptr; t = strtok_r(nullptr, " ", &save)) {
        if (++tok == 20) { starttime = atol(t); break; }
    }
    return starttime;
}

static void gracefulKillWorker(int pid, int timeoutMs, long expectedStarttime) {
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    while (std::chrono::steady_clock::now() < deadline) {
        if (kill(pid, 0) != 0) return;          // gone
        long st = readStarttimeRaw(pid);
        if (st == -1) return;                   // gone
        if (expectedStarttime != -1 && st != expectedStarttime) return; // pid reused
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    // Re-verify identity right before escalating.
    if (kill(pid, 0) == 0) {
        long st = readStarttimeRaw(pid);
        if (st != -1 && (expectedStarttime == -1 || st == expectedStarttime)) {
            kill(pid, SIGKILL);
        }
    }
}

// Parses files that expose GPU busy time. Supports several formats:
//   - single percentage value ("50")
//   - busy/total pairs separated by whitespace, '@' or '/' ("1234 5678", "1234@5678")
// Returns usage 0..100, or -1 when the file is unreadable/unsupported.
static int readBusyPercentageFile(const std::string& path) {
    std::ifstream file(path);
    std::string line;
    if (!std::getline(file, line)) return -1;

    for (char& c : line) {
        if (!std::isdigit(static_cast<unsigned char>(c))) c = ' ';
    }

    std::istringstream iss(line);
    long a = -1, b = -1;
    if (!(iss >> a)) return -1;
    if (iss >> b) {
        if (b > 0) return std::clamp((int)(a * 100 / b), 0, 100);
    } else if (a >= 0 && a <= 100) {
        return (int)a;
    }
    return -1;
}

// Scans /sys/class/devfreq for a GPU-related node exposing a "load" file.
// Works on many SoCs (Exynos, MediaTek, Kirin, etc.).
static int readDevfreqGpuLoad() {
    const fs::path base("/sys/class/devfreq");
    std::error_code ec;
    if (!fs::is_directory(base, ec)) return -1;

    for (const auto& entry : fs::directory_iterator(base, ec)) {
        if (ec) break;
        std::string name = toLower(entry.path().filename().string());
        if (name.find("gpu") == std::string::npos &&
            name.find("kgsl") == std::string::npos &&
            name.find("mali") == std::string::npos &&
            name.find("midgard") == std::string::npos &&
            name.find("panfrost") == std::string::npos) {
            continue;
        }
        int load = readBusyPercentageFile((entry.path() / "load").string());
        if (load >= 0) return load;
    }
    return -1;
}

int calculateGpuUsage() {
    // Qualcomm Adreno (KGSL)
    int usage = readBusyPercentageFile("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage");
    if (usage >= 0) return usage;
    usage = readBusyPercentageFile("/sys/class/kgsl/kgsl-3d0/gpubusy");
    if (usage >= 0) return usage;
    usage = readBusyPercentageFile("/sys/class/kgsl/kgsl-3d0/gpu_busy");
    if (usage >= 0) return usage;

    // ARM Mali
    usage = readBusyPercentageFile("/sys/class/misc/mali0/device/utilization");
    if (usage >= 0) return usage;
    usage = readBusyPercentageFile("/sys/class/misc/mali0/device/gpu_busy_percentage");
    if (usage >= 0) return usage;
    usage = readBusyPercentageFile("/proc/mali/utilization");
    if (usage >= 0) return usage;

    // Samsung Exynos / generic
    usage = readBusyPercentageFile("/sys/kernel/gpu/gpu_busy");
    if (usage >= 0) return usage;
    usage = readBusyPercentageFile("/sys/kernel/gpu/gpu_busy_percentage");
    if (usage >= 0) return usage;

    // Root-only debugfs paths
    usage = readBusyPercentageFile("/sys/kernel/debug/kgsl/kgsl-3d0/gpubusy");
    if (usage >= 0) return usage;
    usage = readBusyPercentageFile("/d/kgsl/kgsl-3d0/gpubusy");
    if (usage >= 0) return usage;

    // Generic devfreq load
    usage = readDevfreqGpuLoad();
    if (usage >= 0) return usage;

    return -1;
}

bool killProcess(int pid) {
    if (kill(pid, SIGKILL) == 0) return true;
    std::cerr << "Failed to kill process " << pid << ": " << strerror(errno) << std::endl;
    return false;
}

bool killProcessGroup(pid_t pgid, int signal = SIGKILL) {
    return kill(-pgid, signal) == 0;
}

struct Proc {
    int pid;
    std::string name;
    int nice;
    int uid;
    float cpuUsage;
    int parentPid;
    bool isForeground;
    long memoryUsageKb;
    std::string cmdLine;
    std::string state;
    int threads;
    long startTime;
    float elapsedTime;
    long residentSetSizeKb;
    long virtualMemoryKb;
    std::string cgroup;
    std::string executablePath;
    long cpuTimeTicks; // raw utime+stime, for CPU-time display modes
};

long getSystemUptime() {
    std::ifstream uptime("/proc/uptime");
    double uptimeSeconds = 0.0;
    if (uptime.is_open()) uptime >> uptimeSeconds;
    return static_cast<long>(uptimeSeconds * sysconf(_SC_CLK_TCK));
}

// Reads utime+stime (in clock ticks) and starttime for a pid. Returns false if
// the process vanished or the file is unreadable.
bool readProcCpuFields(int pid, long &totalTime, long &startTime) {
    std::string statPath = "/proc/" + std::to_string(pid) + "/stat";
    std::ifstream statFile(statPath);
    if (!statFile.is_open()) return false;
    std::string line;
    if (!std::getline(statFile, line)) return false;
    size_t lastParen = line.rfind(')');
    if (lastParen == std::string::npos) return false;
    std::istringstream iss(line.substr(lastParen + 2));
    std::string state;
    long utime = 0, stime = 0;
    for (int i = 0; i < 11; ++i) { std::string dummy; iss >> dummy; }
    iss >> utime >> stime;
    for (int i = 0; i < 6; ++i) { std::string dummy; iss >> dummy; }
    iss >> startTime;
    totalTime = utime + stime;
    return true;
}

float calculateProcessCpuUsage(int pid) {
    long totalTime = 0, startTime = 0;
    if (!readProcCpuFields(pid, totalTime, startTime)) return 0.0f;

    // Prefer the windowed delta; fall back to the lifetime average the first
    // time the pid is seen (keeps v1 clients working too).
    float windowed = windowedProcCpuUsage(pid, totalTime);
    if (windowed >= 0.0f) return windowed;

    long uptime = getSystemUptime();
    long elapsedTime = uptime - startTime;
    if (elapsedTime > 0) return (100.0f * totalTime) / elapsedTime;
    return 0.0f;
}

std::string getExecutablePath(int pid) {
    std::string exePath = "/proc/" + std::to_string(pid) + "/exe";
    char path[PATH_MAX];
    ssize_t len = readlink(exePath.c_str(), path, sizeof(path) - 1);
    if (len != -1) { path[len] = '\0'; return std::string(path); }
    return "";
}

// ---------------------------------------------------------------------------
// LIST_PROCESS hot path. A scan used to open ~11 files per pid (stat three
// times, /proc/uptime twice, status, comm, cmdline, oom_score_adj, cgroup,
// exe) -- ~10k opens every 5 s on a phone with ~950 processes. Now:
//  - /proc/<pid>/stat is read ONCE with raw open/read and supplies name,
//    state, ppid, nice, threads, utime+stime, starttime, vsize and rss
//    (everything status was read for, except the uid);
//  - uptime is read once per scan;
//  - fields that never change for a given process (cmdline, exe, uid) are
//    cached per (pid, starttime) once the process is a few seconds old, so a
//    freshly forked app process still picks up its final name;
//  - only oom_score_adj (foreground) and cgroup are re-read every scan.
// ---------------------------------------------------------------------------

// Reads a small /proc file into buf (NUL-terminated). Returns bytes read, or -1.
static ssize_t readSmall(const char* path, char* buf, size_t cap) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = read(fd, buf, cap - 1);
    close(fd);
    if (n < 0) return -1;
    buf[n] = '\0';
    return n;
}

struct StatFields {
    std::string comm;
    char state = '?';
    int ppid = 0;
    long utime = 0, stime = 0;
    int nice = 0;
    int threads = 0;
    long starttime = 0;
    unsigned long vsize = 0;  // bytes
    long rssPages = 0;
};

// Parses one /proc/<pid>/stat line. comm may contain spaces and ')' so it is
// taken between the first '(' and the LAST ')'.
static bool parseStatLine(const char* buf, StatFields& f) {
    const char* open_ = strchr(buf, '(');
    const char* close_ = strrchr(buf, ')');
    if (!open_ || !close_ || close_ < open_ || close_[1] != ' ') return false;
    f.comm.assign(open_ + 1, close_ - open_ - 1);
    // Token N after ") " is stat field N+2.
    const char* q = close_ + 2;
    int field = 3;
    while (*q && field <= 24) {
        char* end = nullptr;
        if (field == 3) {
            f.state = *q;
            while (*q && *q != ' ') ++q;
        } else {
            long long v = strtoll(q, &end, 10);
            switch (field) {
                case 4: f.ppid = (int)v; break;
                case 14: f.utime = (long)v; break;
                case 15: f.stime = (long)v; break;
                case 19: f.nice = (int)v; break;
                case 20: f.threads = (int)v; break;
                case 22: f.starttime = (long)v; break;
                case 23: f.vsize = (unsigned long)v; break;
                case 24: f.rssPages = (long)v; break;
                default: break;
            }
            q = end ? end : q;
            while (*q && *q != ' ') ++q;
        }
        while (*q == ' ') ++q;
        ++field;
    }
    return field > 24;
}

// Same wording /proc/<pid>/status uses, so the app's "state" text is unchanged.
static std::string stateLabel(char c) {
    switch (c) {
        case 'R': return "R (running)";
        case 'S': return "S (sleeping)";
        case 'D': return "D (disk sleep)";
        case 'T': return "T (stopped)";
        case 't': return "t (tracing stop)";
        case 'Z': return "Z (zombie)";
        case 'X': return "X (dead)";
        case 'I': return "I (idle)";
        case 'P': return "P (parked)";
        default: return std::string(1, c);
    }
}

struct StaticProcInfo {
    long starttime;
    std::string cmdLine;
    std::string exe;
    int uid;
};

// Only touched from the command loop (LIST_PROCESS), no lock needed.
static std::unordered_map<int, StaticProcInfo> g_staticProcInfo;
static constexpr double STATIC_CACHE_MIN_AGE_S = 5.0;

static int readUid(int pid) {
    char path[64];
    char buf[2048];
    snprintf(path, sizeof(path), "/proc/%d/status", pid);
    if (readSmall(path, buf, sizeof(buf)) <= 0) return -1;
    const char* u = strstr(buf, "\nUid:");
    return u ? atoi(u + 5) : -1;
}

static StaticProcInfo readStaticInfo(int pid, long starttime) {
    StaticProcInfo info{starttime, "", "", -1};
    char path[64];
    char buf[4096];
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    if (readSmall(path, buf, sizeof(buf)) > 0) info.cmdLine = buf; // argv[0] up to the first NUL
    info.exe = getExecutablePath(pid);
    info.uid = readUid(pid);
    return info;
}

static int g_pageKb = 4;

static bool readProcFast(int pid, long uptimeTicks, double clkTck, bool withCgroup, Proc& p) {
    char path[64];
    char buf[1024];
    snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    if (readSmall(path, buf, sizeof(buf)) <= 0) return false;
    StatFields f;
    if (!parseStatLine(buf, f)) return false;

    p = Proc{};
    p.pid = pid;
    p.name = f.comm;
    p.nice = f.nice;
    p.parentPid = f.ppid;
    p.state = stateLabel(f.state);
    p.threads = f.threads;
    p.startTime = f.starttime;
    p.elapsedTime = static_cast<float>(uptimeTicks - f.starttime) / clkTck;
    p.residentSetSizeKb = f.rssPages * g_pageKb;
    p.memoryUsageKb = p.residentSetSizeKb;
    p.virtualMemoryKb = (long)(f.vsize / 1024);
    p.cpuTimeTicks = f.utime + f.stime;

    // Static fields: cached once the process has settled.
    auto it = g_staticProcInfo.find(pid);
    if (it != g_staticProcInfo.end() && it->second.starttime == f.starttime) {
        p.cmdLine = it->second.cmdLine;
        p.executablePath = it->second.exe;
        p.uid = it->second.uid;
    } else {
        StaticProcInfo info = readStaticInfo(pid, f.starttime);
        p.cmdLine = info.cmdLine;
        p.executablePath = info.exe;
        p.uid = info.uid;
        if (p.elapsedTime >= STATIC_CACHE_MIN_AGE_S) g_staticProcInfo[pid] = std::move(info);
        else if (it != g_staticProcInfo.end()) g_staticProcInfo.erase(it); // pid reused
    }

    // CPU%: windowed delta, lifetime average the first time a pid is seen.
    float windowed = windowedProcCpuUsage(pid, p.cpuTimeTicks);
    if (windowed >= 0.0f) {
        p.cpuUsage = windowed;
    } else {
        long alive = uptimeTicks - f.starttime;
        p.cpuUsage = alive > 0 ? (100.0f * p.cpuTimeTicks) / alive : 0.0f;
    }

    // Dynamic fields, re-read every scan.
    snprintf(path, sizeof(path), "/proc/%d/oom_score_adj", pid);
    p.isForeground = readSmall(path, buf, sizeof(buf)) > 0 && atoi(buf) <= 100;
    // cgroup is opt-in (LIST_PROCESS "cgroup": true): the app never shows
    // it, and it was a third of the per-pid reads on every scan.
    snprintf(path, sizeof(path), "/proc/%d/cgroup", pid);
    if (withCgroup && readSmall(path, buf, sizeof(buf)) > 0) {
        char* nl = strchr(buf, '\n');
        if (nl) *nl = '\0';
        char* colon = strrchr(buf, ':');
        p.cgroup = colon ? std::string(colon + 1) : std::string(buf);
    }
    return true;
}

static void pruneStaticProcInfo(const std::vector<int>& livePids) {
    std::unordered_set<int> live(livePids.begin(), livePids.end());
    for (auto it = g_staticProcInfo.begin(); it != g_staticProcInfo.end();) {
        if (live.count(it->first) == 0) it = g_staticProcInfo.erase(it);
        else ++it;
    }
}

json procToJson(const Proc &p) {
    return {
        {"pid", p.pid}, {"name", p.name}, {"nice", p.nice}, {"uid", p.uid},
        {"cpuUsage", p.cpuUsage}, {"parentPid", p.parentPid}, {"isForeground", p.isForeground},
        {"memoryUsageKb", p.memoryUsageKb}, {"cmdLine", p.cmdLine}, {"state", p.state},
        {"threads", p.threads}, {"startTime", p.startTime}, {"elapsedTime", p.elapsedTime},
        {"residentSetSizeKb", p.residentSetSizeKb}, {"virtualMemoryKb", p.virtualMemoryKb},
        {"cgroup", p.cgroup}, {"executablePath", p.executablePath},
        {"cpuTimeTicks", p.cpuTimeTicks}
    };
}

std::vector<Proc> collectProcs(bool withCgroup) {
    std::vector<Proc> procs;
    std::vector<int> pids = listPids();
    procs.reserve(pids.size());
    long uptime = getSystemUptime();
    double clk = (double)sysconf(_SC_CLK_TCK);
    long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize > 0) g_pageKb = (int)(pageSize / 1024);
    for (int pid : pids) {
        try {
            Proc p;
            if (readProcFast(pid, uptime, clk, withCgroup, p)) procs.push_back(std::move(p));
        } catch (...) {}
    }
    pruneProcCpuSamples(pids);
    pruneStaticProcInfo(pids);
    return procs;
}

void getSwapUsage(long &used, long &total) {
    used = 0; total = 0;
    std::ifstream meminfo("/proc/meminfo");
    if (!meminfo.is_open()) return;
    long totalKB = 0, freeKB = 0;
    std::string line;
    while (std::getline(meminfo, line)) {
        if (line.compare(0, 10, "SwapTotal:") == 0) totalKB = std::stol(line.substr(10));
        else if (line.compare(0, 9, "SwapFree:") == 0) freeKB = std::stol(line.substr(9));
    }
    used = (totalKB - freeKB) * 1024;
    total = totalKB * 1024;
}

struct NetStat {
    unsigned long long rxBytes;
    unsigned long long txBytes;
};

struct NetInterfaceInfo {
    std::string name;
    unsigned long long totalBytes;
};

std::vector<NetInterfaceInfo> listNetInterfaces() {
    std::vector<NetInterfaceInfo> interfaces;
    std::ifstream netdev("/proc/net/dev");
    std::string line;
    if (!netdev.is_open()) return interfaces;

    std::getline(netdev, line);
    std::getline(netdev, line);
    while (std::getline(netdev, line)) {
        size_t colon = line.find(':');
        if (colon != std::string::npos) {
            std::string name = line.substr(0, colon);
            name.erase(0, name.find_first_not_of(' '));
            if (name == "lo") continue;

            std::istringstream iss(line.substr(colon + 1));
            unsigned long long rxBytes, txBytes, dummy;
            iss >> rxBytes;
            for(int i=0; i<7; ++i) iss >> dummy;
            iss >> txBytes;

            interfaces.push_back({name, rxBytes + txBytes});
        }
    }
    return interfaces;
}

NetStat getNetStat(const std::string& iface) {
    std::ifstream netdev("/proc/net/dev");
    std::string line;
    while (std::getline(netdev, line)) {
        if (line.find(iface + ":") != std::string::npos) {
            std::istringstream iss(line.substr(line.find(':') + 1));
            unsigned long long rxBytes, dummy;
            unsigned long long txBytes;
            iss >> rxBytes;
            for(int i=0; i<7; ++i) iss >> dummy;
            iss >> txBytes;
            return {rxBytes, txBytes};
        }
    }
    return {0, 0};
}

struct NetStatSnapshot {
    unsigned long long rxBytes;
    unsigned long long txBytes;
    std::chrono::steady_clock::time_point timestamp;
};

static std::unordered_map<std::string, NetStatSnapshot> netStatCache;

// Byte counters are unsigned and reset when an interface goes down/up
// (Wi-Fi reconnect, airplane mode). A plain `curr - prev` then wraps to
// ~1.8e19 and the graph shows an absurd spike; report 0 for that window.
static double counterRate(unsigned long long curr, unsigned long long prev, double dt) {
    if (dt <= 0.0 || curr < prev) return 0.0;
    return (double)(curr - prev) / dt;
}

// ---------------------------------------------------------------------------
// Feature builders for the Phase-2 commands (CORE_PING, BATTERY_PING, PSS_PING,
// SUBSCRIBE push payloads).
// ---------------------------------------------------------------------------

static long readFileLong(const std::string& path, long def) {
    std::ifstream f(path);
    long v = def;
    if (f.is_open() && (f >> v)) return v;
    return def;
}

static std::string readFileTrimmed(const std::string& path) {
    std::ifstream f(path);
    std::string s;
    if (f.is_open() && std::getline(f, s)) {
        size_t a = s.find_first_not_of(" \t\r\n");
        size_t b = s.find_last_not_of(" \t\r\n");
        if (a != std::string::npos) return s.substr(a, b - a + 1);
    }
    return "";
}

// Per-core snapshot: usage from the sampler's atomics, frequencies from
// cpufreq sysfs read on demand. Unknown values are reported as -1.
static json buildCoresJson() {
    json arr = json::array();
    int n = std::min(g_coreCount.load(), MAX_CORES);
    for (int i = 0; i < n; ++i) {
        std::string cpufreq = "/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/";
        long cur = readFileLong(cpufreq + "scaling_cur_freq", -1);
        long mn = readFileLong(cpufreq + "cpuinfo_min_freq", -1);
        long mx = readFileLong(cpufreq + "cpuinfo_max_freq", -1);
        // Policy ceiling: thermal/power HALs lower scaling_max_freq below the
        // hardware max when they throttle; that gap is the real "throttled".
        long lim = readFileLong(cpufreq + "scaling_max_freq", -1);
        bool online = true;
        std::string onlinePath = "/sys/devices/system/cpu/cpu" + std::to_string(i) + "/online";
        struct stat st;
        if (stat(onlinePath.c_str(), &st) == 0) {
            online = readFileLong(onlinePath, 1) != 0;
        }
        if (!online) cur = -1;
        arr.push_back({
            {"index", i},
            {"online", online},
            {"usage", g_coreUsage[i].load()},
            {"freqKHz", cur},
            {"minFreqKHz", mn},
            {"maxFreqKHz", mx},
            {"limitFreqKHz", lim},
        });
    }
    return arr;
}

// Locates the main battery node. Vendor trees vary (battery/bms/Battery);
// the first directory that actually exposes capacity/voltage/current wins.
static std::string findBatteryBase() {
    static const char* candidates[] = {
        "/sys/class/power_supply/battery",
        "/sys/class/power_supply/bms",
        "/sys/class/power_supply/Battery",
    };
    for (const char* c : candidates) {
        std::error_code ec;
        if (!fs::is_directory(c, ec)) continue;
        for (const char* f : {"capacity", "voltage_now", "current_now"}) {
            std::ifstream t(std::string(c) + "/" + f);
            if (t.is_open()) return c;
        }
    }
    return "";
}

// Some kernels report current_now in mA instead of µA. Values under 10 A
// worth of µA (i.e. |raw| < 10000) can only be sane as mA on a phone, so
// scale them up. Sign convention is vendor-dependent and NOT normalized:
// the `charging` flag (from `status`) is the source of truth for direction.
static long long normalizeCurrentUA(long long raw) {
    long long a = raw < 0 ? -raw : raw;
    if (a > 0 && a < 10000) return raw * 1000;
    return raw;
}

static json buildBatteryJson() {
    json j;
    j["type"] = "BATTERY_STATS";
    std::string base = findBatteryBase();
    if (base.empty() || readFileLong(base + "/present", 1) == 0) {
        j["present"] = false;
        return j;
    }
    j["present"] = true;

    long cap = readFileLong(base + "/capacity", -1);
    j["capacity"] = (cap >= 0 && cap <= 100) ? cap : -1;

    std::string status = readFileTrimmed(base + "/status");
    j["status"] = status;
    // "Full" means plugged in and topped off: same direction as Charging,
    // matching the app's BATTERY_STATUS_FULL fallback (no sign flip at 100%).
    j["charging"] = (status == "Charging" || status == "Full");
    j["health"] = readFileTrimmed(base + "/health");

    long long volt = -1;
    long long curRaw = 0;
    bool hasVolt = false, hasCur = false;
    {
        std::ifstream f(base + "/voltage_now");
        long long v;
        if (f.is_open() && (f >> v)) { volt = v; hasVolt = true; }
    }
    {
        std::ifstream f(base + "/current_now");
        long long v;
        if (f.is_open() && (f >> v)) { curRaw = v; hasCur = true; }
    }
    j["voltageUV"] = hasVolt ? volt : -1;
    long long curUA = hasCur ? normalizeCurrentUA(curRaw) : -1;
    j["currentUA"] = curUA;
    // Unscaled sysfs value, so the app can apply a user-chosen unit when the
    // mA/uA heuristic above guesses wrong for a device.
    j["currentRaw"] = hasCur ? curRaw : -1;

    // µV * µA = pW; /1e6 → µW. Reported as magnitude (sign varies by vendor).
    long long powerUW = -1;
    if (hasVolt && hasCur) powerUW = std::llabs(volt * curUA) / 1000000LL;
    j["powerUW"] = powerUW;

    j["tempTenthsC"] = readFileLong(base + "/temp", -1);
    j["cycleCount"] = getBatteryCycleCount().value_or(-1);
    j["chargeCounterUAh"] = readFileLong(base + "/charge_counter", -1);
    j["chargeFullUAh"] = readFileLong(base + "/charge_full", -1);
    j["chargeFullDesignUAh"] = readFileLong(base + "/charge_full_design", -1);
    return j;
}

static json buildMemJson() {
    std::ifstream f("/proc/meminfo");
    long total = -1, avail = -1, swapTotal = -1, swapFree = -1;
    std::string line;
    while (std::getline(f, line)) {
        if (line.compare(0, 9, "MemTotal:") == 0) total = std::atol(line.c_str() + 9);
        else if (line.compare(0, 13, "MemAvailable:") == 0) avail = std::atol(line.c_str() + 13);
        else if (line.compare(0, 10, "SwapTotal:") == 0) swapTotal = std::atol(line.c_str() + 10);
        else if (line.compare(0, 9, "SwapFree:") == 0) swapFree = std::atol(line.c_str() + 9);
    }
    return {
        {"totalKb", total},
        {"availableKb", avail},
        {"swapTotalKb", swapTotal},
        {"swapUsedKb", (swapTotal >= 0 && swapFree >= 0) ? swapTotal - swapFree : -1},
    };
}

struct PssInfo {
    bool available = false;
    long rssKb = -1, pssKb = -1, pssAnonKb = -1, pssFileKb = -1, swapPssKb = -1, privateKb = -1;
};

// Reads /proc/<pid>/smaps_rollup (kernel >= 4.14). available=false when the
// node is missing (old kernel) or the process vanished.
static PssInfo readPssInfo(int pid) {
    PssInfo info;
    std::ifstream f("/proc/" + std::to_string(pid) + "/smaps_rollup");
    if (!f.is_open()) return info;
    std::string line;
    long privClean = -1, privDirty = -1;
    bool any = false;
    while (std::getline(f, line)) {
        size_t colon = line.find(':');
        if (colon == std::string::npos) continue;
        std::string key = line.substr(0, colon);
        long val = std::strtol(line.c_str() + colon + 1, nullptr, 10);
        // Full-key compare: "Pss" must not match "Pss_Anon".
        if (key == "Rss") { info.rssKb = val; any = true; }
        else if (key == "Pss") { info.pssKb = val; any = true; }
        else if (key == "Pss_Anon") { info.pssAnonKb = val; }
        else if (key == "Pss_File") { info.pssFileKb = val; }
        else if (key == "SwapPss") { info.swapPssKb = val; }
        else if (key == "Private_Clean") { privClean = val; }
        else if (key == "Private_Dirty") { privDirty = val; }
    }
    if (privClean >= 0 && privDirty >= 0) info.privateKb = privClean + privDirty;
    info.available = any;
    return info;
}

// ---------------------------------------------------------------------------
// Push mode: a single subscription at a time; one dedicated thread emits a
// frame per topic every interval. Frames share the stdout write mutex with
// regular responses, so they never interleave. UNSUBSCRIBE / daemon shutdown
// join the thread before acknowledging, so no frame can trail its ack.
// ---------------------------------------------------------------------------

struct PusherState {
    std::thread th;
    std::atomic<bool> active{true};
    std::condition_variable cv;
    std::mutex cvMutex;
    // Per-subscription net delta cache (independent of the NET_PING one).
    std::unordered_map<std::string, NetStatSnapshot> netCache;
    std::mutex netMutex;
};

static std::mutex g_pushers_mutex;
static std::vector<std::shared_ptr<PusherState>> g_pushers;

static json buildNetPushData(PusherState& st) {
    std::ifstream netdev("/proc/net/dev");
    std::string line;
    auto now = std::chrono::steady_clock::now();
    json ifaces = json::array();
    if (netdev.is_open()) {
        std::getline(netdev, line); // header
        std::getline(netdev, line);
        while (std::getline(netdev, line)) {
            size_t colon = line.find(':');
            if (colon == std::string::npos) continue;
            std::string name = line.substr(0, colon);
            name.erase(0, name.find_first_not_of(' '));
            std::istringstream iss(line.substr(colon + 1));
            unsigned long long rx = 0, tx = 0, dummy;
            iss >> rx;
            for (int i = 0; i < 7; ++i) iss >> dummy;
            iss >> tx;

            double rxPerSec = 0, txPerSec = 0;
            {
                std::lock_guard<std::mutex> lock(st.netMutex);
                auto it = st.netCache.find(name);
                if (it != st.netCache.end()) {
                    double dt = std::chrono::duration<double>(now - it->second.timestamp).count();
                    if (dt > 0) {
                        rxPerSec = counterRate(rx, it->second.rxBytes, dt);
                        txPerSec = counterRate(tx, it->second.txBytes, dt);
                    }
                }
                st.netCache[name] = {rx, tx, now};
            }
            ifaces.push_back({
                {"name", name},
                {"rxBytes", rx},
                {"txBytes", tx},
                {"rxBytesPerSec", rxPerSec},
                {"txBytesPerSec", txPerSec},
            });
        }
    }
    return {{"interfaces", ifaces}};
}

static json buildPushData(const std::string& topic, PusherState& st) {
    if (topic == "cpu") {
        return {
            {"usage", g_cpuUsage.load()},
            {"temp", getCpuTemperatureCelsius()},
            {"cores", buildCoresJson()},
        };
    }
    if (topic == "battery") return buildBatteryJson();
    if (topic == "mem") return buildMemJson();
    if (topic == "net") return buildNetPushData(st);
    return json::object();
}

static void pusherLoop(std::shared_ptr<PusherState> st, std::vector<std::string> topics, int intervalMs) {
    long seq = 0;
    while (st->active.load() && keep_running.load()) {
        for (const auto& topic : topics) {
            if (!st->active.load() || !keep_running.load()) break;
            json frame;
            frame["type"] = "PUSH";
            frame["topic"] = topic;
            frame["seq"] = seq;
            frame["data"] = buildPushData(topic, *st);
            if (!send_json(frame)) { st->active.store(false); break; }
        }
        if (!st->active.load()) break;
        ++seq;
        std::unique_lock<std::mutex> lock(st->cvMutex);
        st->cv.wait_for(lock, std::chrono::milliseconds(intervalMs),
                        [&] { return !st->active.load() || !keep_running.load(); });
    }
}

static void stopPushersAndJoin() {
    std::vector<std::shared_ptr<PusherState>> to_join;
    {
        std::lock_guard<std::mutex> lock(g_pushers_mutex);
        to_join.swap(g_pushers);
    }
    for (auto& st : to_join) {
        st->active.store(false);
    }
    for (auto& st : to_join) {
        st->cv.notify_all();
    }
    for (auto& st : to_join) {
        if (st->th.joinable()) st->th.join();
    }
}

// Replaces any running subscription with a new one. topics is pre-validated.
static void startSubscription(const std::vector<std::string>& topics, int intervalMs) {
    stopPushersAndJoin();
    auto st = std::make_shared<PusherState>();
    st->th = std::thread(pusherLoop, st, topics, intervalMs);
    std::lock_guard<std::mutex> lock(g_pushers_mutex);
    g_pushers.push_back(std::move(st));
}

// ---------------------------------------------------------------------------
// Orphan reaper: only one daemon per app install should ever run. On start,
// SIGKILL every other process executing the very same binary (same
// /proc/<pid>/exe target), so leaked instances from older app builds or
// crashed connections cannot pile up and keep sampling forever.
// ---------------------------------------------------------------------------

static int reapSiblingDaemons() {
    char self[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (n <= 0) return 0;
    self[n] = '\0';
    std::string selfExe(self);
    int me = getpid();
    int reaped = 0;
    for (int pid : listPids()) {
        if (pid == me) continue;
        char path[64];
        char target[PATH_MAX];
        snprintf(path, sizeof(path), "/proc/%d/exe", pid);
        ssize_t m = readlink(path, target, sizeof(target) - 1);
        if (m <= 0) continue;
        target[m] = '\0';
        if (selfExe == target && kill(pid, SIGKILL) == 0) ++reaped;
    }
    return reaped;
}

static int g_reaped = 0;
static int g_reapedCount() { return g_reaped; }

// FREEZE/THAW: SIGSTOP / SIGCONT. Refuses init, ourselves and our parent
// (the su/Shizuku shell feeding our stdin).
static bool signalFreeze(int pid, bool freeze) {
    if (pid <= 1 || pid == getpid() || pid == getppid()) return false;
    return kill(pid, freeze ? SIGSTOP : SIGCONT) == 0;
}

// Runs a shell command and returns its stdout (bounded).
static std::string runCapture(const char* cmd, size_t maxBytes = 16 * 1024 * 1024) {
    std::string out;
    FILE* f = popen(cmd, "r");
    if (!f) return out;
    char buf[4096];
    size_t r;
    while ((r = fread(buf, 1, sizeof(buf), f)) > 0) {
        if (out.size() + r > maxBytes) break;
        out.append(buf, r);
    }
    pclose(f);
    return out;
}

// APP_POWER: per-uid estimated mAh and partial-wakelock time since the last
// full charge, parsed from `dumpsys batterystats --checkin`:
//   9,<uid>,l,pwi,uid,<mAh>,...
//   9,<uid>,l,wl,<name>,<fullMs>,f,<count>,<partialMs>,p,<count>,...
// Wakelock names may contain commas, so the partial time is located by the
// "p" marker rather than by a fixed column.
static json buildAppPowerJson() {
    std::string dump = runCapture("dumpsys batterystats --checkin 2>/dev/null");
    std::unordered_map<int, double> mah;
    std::unordered_map<int, long long> wlMs;
    std::unordered_map<int, int> wlCount;
    std::istringstream in(dump);
    std::string line;
    while (std::getline(in, line)) {
        if (line.rfind("9,", 0) != 0) continue;
        std::vector<std::string> f;
        std::string cell;
        std::istringstream ls(line);
        while (std::getline(ls, cell, ',')) f.push_back(cell);
        if (f.size() < 6 || f[2] != "l") continue;
        int uid = std::atoi(f[1].c_str());
        if (f[3] == "pwi" && f[4] == "uid") {
            mah[uid] += std::atof(f[5].c_str());
        } else if (f[3] == "wl") {
            for (size_t i = 6; i + 1 < f.size(); ++i) {
                if (f[i] == "p") {
                    wlMs[uid] += std::atoll(f[i - 1].c_str());
                    wlCount[uid] += std::atoi(f[i + 1].c_str());
                    break;
                }
            }
        }
    }
    std::unordered_set<int> uids;
    for (auto& kv : mah) uids.insert(kv.first);
    for (auto& kv : wlMs) uids.insert(kv.first);
    json apps = json::array();
    for (int uid : uids) {
        apps.push_back({
            {"uid", uid},
            {"mAh", mah.count(uid) ? mah[uid] : 0.0},
            {"wakelockMs", wlMs.count(uid) ? wlMs[uid] : 0LL},
            {"wakelockCount", wlCount.count(uid) ? wlCount[uid] : 0},
        });
    }
    return {{"type", "APP_POWER"}, {"available", !dump.empty()}, {"apps", apps}};
}

void processCommand(const std::string &received) {
    json j_out;
    bool respond = true;
    try {
        json j_in = json::parse(received);
        std::string cmd = j_in.value("cmd", "");

        if (cmd == "PING") {
            j_out["type"] = "PONG";
        } else if (cmd == "HELLO") {
            j_out = helloMessage();
        } else if (cmd == "FREEZE" || cmd == "THAW") {
            int pid = j_in.value("pid", -1);
            j_out["type"] = "FREEZE_RESULT";
            j_out["pid"] = pid;
            j_out["frozen"] = (cmd == "FREEZE");
            j_out["success"] = signalFreeze(pid, cmd == "FREEZE");
        } else if (cmd == "APP_POWER") {
            // dumpsys takes seconds: answer from a worker so the command
            // loop (graphs, widget, kills) never stalls behind it. send_json
            // is mutex-protected; the id is echoed by hand here.
            json id = j_in.contains("id") ? j_in["id"] : json();
            std::thread([id]() {
                json out = buildAppPowerJson();
                if (!id.is_null()) out["id"] = id;
                send_json(out);
            }).detach();
            respond = false;
        } else if (cmd == "KILL") {
            int pid = j_in.value("pid", -1);
            bool success = (pid > 0) && killProcess(pid);
            j_out["type"] = "KILL_RESULT";
            j_out["success"] = success;
        } else if (cmd == "KILL_GRACEFUL") {
            // Deliver SIGTERM immediately, answer right away, and let a
            // detached worker escalate to SIGKILL if the process is still
            // alive after the grace period. Never blocks the command loop.
            int pid = j_in.value("pid", -1);
            int timeoutMs = std::clamp(j_in.value("timeoutMs", 3000), 200, 10000);
            bool success = false;
            if (pid > 0) {
                long starttime = readStarttimeRaw(pid);
                if (starttime == -1) {
                    success = true; // already gone — goal achieved
                } else if (kill(pid, SIGTERM) == 0) {
                    success = true;
                    std::thread(gracefulKillWorker, pid, timeoutMs, starttime).detach();
                }
            }
            j_out["type"] = "KILL_RESULT";
            j_out["success"] = success;
            j_out["graceful"] = true;
            j_out["forced"] = false;
            j_out["pid"] = pid;
        } else if (cmd == "FORCE_STOP") {
            std::string pkg = j_in.value("pkg", "");
            bool success = false;
            if (isValidPackageName(pkg)) {
                std::string scmd = "am force-stop " + pkg;
                success = (system(scmd.c_str()) == 0);
            }
            j_out["type"] = "KILL_RESULT";
            j_out["success"] = success;
        } else if (cmd == "KILL_GROUP") {
            int pgid = j_in.value("pgid", -1);
            bool success = (pgid > 0) ? killProcessGroup(pgid) : false;
            j_out["type"] = "KILL_RESULT";
            j_out["success"] = success;
        } else if (cmd == "STOP_SELF" || cmd == "BUSY") {
            keep_running.store(false);
            respond = false;
        } else if (cmd == "LIST_PROCESS") {
            auto procs = collectProcs(j_in.value("cgroup", false));
            json procs_j = json::array();
            for (const auto &p : procs) procs_j.push_back(procToJson(p));
            j_out["type"] = "PROCESS_LIST";
            j_out["processes"] = procs_j;
        } else if (cmd == "CPU_PING") {
            j_out["type"] = "CPU_USAGE";
            j_out["usage"] = g_cpuUsage.load();
        } else if (cmd == "SWAP_PING") {
            long used, total;
            getSwapUsage(used, total);
            j_out["type"] = "SWAP_USAGE";
            j_out["used"] = used;
            j_out["total"] = total;
        } else if (cmd == "GPU_PING") {
            j_out["type"] = "GPU_USAGE";
            j_out["usage"] = calculateGpuUsage();
        } else if (cmd == "CTEMP_PING") {
            j_out["type"] = "CPU_TEMP";
            j_out["temp"] = getCpuTemperatureCelsius();
        } else if (cmd == "PING_PID_CPU") {
            int pid = j_in.value("pid", -1);
            j_out["type"] = "PROCESS_CPU_USAGE";
            j_out["usage"] = calculateProcessCpuUsage(pid);
        } else if (cmd == "BAT_CHARGE_CYCLES") {
            j_out["type"] = "CHARGE_CYCLES";
            j_out["cycles"] = getBatteryCycleCount().value_or(-1);
        } else if (cmd == "CORE_PING") {
            j_out["type"] = "CORE_USAGE";
            j_out["usage"] = g_cpuUsage.load();
            j_out["cores"] = buildCoresJson();
        } else if (cmd == "BATTERY_PING") {
            j_out = buildBatteryJson();
        } else if (cmd == "PSS_PING") {
            int pid = j_in.value("pid", -1);
            PssInfo info = readPssInfo(pid);
            j_out["type"] = "PSS";
            j_out["pid"] = pid;
            j_out["available"] = info.available;
            if (info.available) {
                j_out["rssKb"] = info.rssKb;
                j_out["pssKb"] = info.pssKb;
                j_out["pssAnonKb"] = info.pssAnonKb;
                j_out["pssFileKb"] = info.pssFileKb;
                j_out["swapPssKb"] = info.swapPssKb;
                j_out["privateKb"] = info.privateKb;
            }
        } else if (cmd == "SUBSCRIBE") {
            std::vector<std::string> topics;
            if (j_in.contains("topics") && j_in["topics"].is_array()) {
                for (const auto& t : j_in["topics"]) {
                    if (t.is_string()) topics.push_back(t.get<std::string>());
                }
            }
            static const std::vector<std::string> kValidTopics = {"cpu", "battery", "mem", "net"};
            bool allValid = !topics.empty();
            for (const auto& t : topics) {
                if (std::find(kValidTopics.begin(), kValidTopics.end(), t) == kValidTopics.end()) allValid = false;
            }
            if (!allValid) {
                j_out["type"] = "ERROR";
                j_out["message"] = "SUBSCRIBE needs topics from: cpu, battery, mem, net";
            } else {
                int intervalMs = std::clamp(j_in.value("intervalMs", 1000), 250, 60000);
                startSubscription(topics, intervalMs);
                bool wantsCpu = std::find(topics.begin(), topics.end(), "cpu") != topics.end();
                g_samplePeriodMs.store(wantsCpu
                    ? std::clamp(intervalMs, 250, SAMPLE_PERIOD_IDLE_MS)
                    : SAMPLE_PERIOD_IDLE_MS);
                j_out["type"] = "SUBSCRIBED";
                j_out["topics"] = topics;
                j_out["intervalMs"] = intervalMs;
            }
        } else if (cmd == "UNSUBSCRIBE") {
            stopPushersAndJoin();
            g_samplePeriodMs.store(SAMPLE_PERIOD_IDLE_MS);
            j_out["type"] = "UNSUBSCRIBED";
        } else if (cmd == "LIST_NET_INTERFACES") {
            auto interfaces = listNetInterfaces();
            json interfaces_j = json::array();
            for (const auto& iface : interfaces) {
                interfaces_j.push_back({{"name", iface.name}, {"totalBytes", iface.totalBytes}});
            }
            j_out["type"] = "NET_INTERFACE_LIST";
            j_out["interfaces"] = interfaces_j;
        } else if (cmd == "NET_PING") {
            std::string iface = j_in.value("interface", "");
            auto now = std::chrono::steady_clock::now();
            auto curr = getNetStat(iface);

            j_out["type"] = "NET_STATS";

            auto it = netStatCache.find(iface);
            if (it != netStatCache.end()) {
                auto& prev = it->second;
                double elapsed = std::chrono::duration<double>(now - prev.timestamp).count();

                if (elapsed > 0.0) {
                    j_out["rxBytesPerSec"] = counterRate(curr.rxBytes, prev.rxBytes, elapsed);
                    j_out["txBytesPerSec"] = counterRate(curr.txBytes, prev.txBytes, elapsed);
                } else {
                    j_out["rxBytesPerSec"] = 0;
                    j_out["txBytesPerSec"] = 0;
                }
            } else {
                j_out["rxBytesPerSec"] = 0;
                j_out["txBytesPerSec"] = 0;
            }

            netStatCache[iface] = {curr.rxBytes, curr.txBytes, now};

            j_out["rxBytes"] = curr.rxBytes;
            j_out["txBytes"] = curr.txBytes;
        } else {
            // Protocol v2: never leave a well-formed request unanswered.
            j_out["type"] = "ERROR";
            j_out["message"] = "unknown command";
            j_out["cmd"] = cmd;
        }

        // Protocol v2: echo the caller's request id so responses can be
        // correlated with requests regardless of arrival order.
        if (respond && j_in.contains("id") && !j_in["id"].is_null()) {
            j_out["id"] = j_in["id"];
        }
    } catch (const std::exception& e) {
        log_line("JSON parse error: " + std::string(e.what()) + " | Data: " + received);
        j_out = json{{"type", "ERROR"}, {"message", "malformed request"}};
        respond = true;
    }

    if (respond && !j_out.is_null()) {
        send_json(j_out);
    }
}

int main() {
    signal(SIGINT, handle_sigint);
    signal(SIGTERM, handle_sigint);
    signal(SIGPIPE, SIG_IGN);

    // Protocol v2 handshake: announce who we are before the app says anything.
    // The app validates proto/caps and refuses to talk to incompatible daemons.
    g_reaped = reapSiblingDaemons();
    if (g_reaped > 0) log_line("reaped " + std::to_string(g_reaped) + " orphaned daemon(s)");
    send_json(helloMessage());

    // Background sampler keeps g_cpuUsage fresh; CPU_PING never blocks.
    std::thread sampler(cpuSamplerLoop);

    const size_t BUF_SIZE = 8192;
    std::unique_ptr<char[]> buf(new char[BUF_SIZE]);
    std::string recv_buffer;

    while (keep_running.load()) {
        ssize_t r = read(STDIN_FILENO, buf.get(), BUF_SIZE - 1);
        if (r > 0) {
            buf[r] = '\0';
            recv_buffer.append(buf.get(), r);
            size_t pos;
            while ((pos = recv_buffer.find('\n')) != std::string::npos) {
                std::string message = recv_buffer.substr(0, pos);
                recv_buffer.erase(0, pos + 1);
                if (!message.empty()) processCommand(message);
            }
        } else if (r == 0) {
            break;
        } else {
            if (errno == EINTR) continue;
            break;
        }
    }

    keep_running.store(false);
    stopPushersAndJoin();
    sampler.join();
    return 0;
}
