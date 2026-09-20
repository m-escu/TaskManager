package com.rk.taskmanager.widget

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import com.rk.commons.settings.Settings
import com.rk.taskmanager.daemon.DaemonClient
import com.rk.taskmanager.daemon.isConnected
import com.rk.taskmanager.settings.WorkingMode
import com.rk.taskmanager.shizuku.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Pure stat collectors for the home-screen widget.
 *
 * CPU sourcing is a fallback chain, because on some devices SELinux blocks
 * untrusted-app reads of /proc/stat (which showed up as a permanently
 * missing CPU column in the field):
 *  1. the daemon's CPU_PING when a connection is alive (exact, cheap),
 *  2. direct /proc/stat deltas (world-readable on stock Android),
 *  3. privileged `cat /proc/stat` via the configured working mode
 *     (su for ROOT, Shizuku shell for SHIZUKU) — grants are already
 *     persisted by normal app usage, so this never prompts.
 *
 * Battery current prefers the daemon's BATTERY_PING heuristics and falls
 * back to the public BATTERY_PROPERTY_CURRENT_NOW property.
 */
object WidgetStats {

    /**
     * Latest /proc/stat sample, shared by the ephemeral and live paths so a
     * warm app process never needs an artificial re-sample delay.
     */
    object CpuStore {
        @Volatile var total: Long = 0L
        @Volatile var idle: Long = 0L
        @Volatile var stampMs: Long = 0L
    }

    private const val PRIVILEGED_MIN_INTERVAL_MS = 250L

    @Volatile
    private var lastPrivilegedReadMs = 0L

    /**
     * Parses a "cpu  user nice system idle iowait ..." line into
     * (total, idle + iowait). Returns null for malformed input.
     */
    fun parseCpuTimes(line: String?): Pair<Long, Long>? {
        if (line == null || !line.startsWith("cpu ")) return null
        val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (parts.size < 4) return null
        val total = parts.sum()
        val idle = parts[3] + if (parts.size > 4) parts[4] else 0L
        return total to idle
    }

    /**
     * Busy-window percentage between two samples; -1 when the window is
     * invalid (first observation or counter wrap).
     */
    fun usagePercent(prevTotal: Long, prevIdle: Long, nowTotal: Long, nowIdle: Long): Int {
        val dTotal = nowTotal - prevTotal
        if (prevTotal <= 0L || dTotal <= 0L) return -1
        val busy = dTotal - (nowIdle - prevIdle)
        return ((busy * 100L) / dTotal).toInt().coerceIn(0, 100)
    }

    /** Daemon CPU usage (0..100), or null when disconnected/unavailable. */
    private suspend fun cpuFromDaemon(): Int? {
        if (!isConnected) return null
        return runCatching {
            DaemonClient.cpu()?.optInt("usage", -1)?.takeIf { it in 0..100 }
        }.getOrNull()
    }

    private fun readProcStatDirect(): String? = runCatching {
        File("/proc/stat").bufferedReader().use { it.readLine() }
    }.getOrNull()

    private fun readProcStatViaSu(): String? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/stat"))
        val line = process.inputStream.bufferedReader().readLine()
        if (process.isAlive) process.waitFor(2, TimeUnit.SECONDS)
        if (process.isAlive) process.destroy()
        line
    }.getOrNull()

    private suspend fun readProcStatViaShizuku(): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (ShizukuShell.isShizukuRunning().not() || ShizukuShell.isPermissionGranted().not()) {
                return@runCatching null
            }
            val process = ShizukuShell.startStreamingProcess(
                cmd = arrayOf<String?>("cat", "/proc/stat"),
                env = arrayOf<String?>(),
                dir = "/",
            )
            process.inputStream.bufferedReader().readLine()
        }.getOrNull()
    }

    /**
     * One /proc/stat first line: direct read when possible, otherwise a
     * throttled privileged read through the configured working mode.
     */
    private suspend fun readProcStatLine(): String? {
        readProcStatDirect()?.let { return it }

        val now = System.currentTimeMillis()
        if (now - lastPrivilegedReadMs < PRIVILEGED_MIN_INTERVAL_MS) return null
        lastPrivilegedReadMs = now

        return when (Settings.workingMode) {
            WorkingMode.ROOT.id -> readProcStatViaSu()
            WorkingMode.SHIZUKU.id -> readProcStatViaShizuku()
            else -> null
        }
    }

    /** Reads /proc/stat, stores the sample in [CpuStore] and returns it. */
    suspend fun sampleCpuTimes(): Pair<Long, Long>? {
        val parsed = parseCpuTimes(readProcStatLine()) ?: return null
        CpuStore.total = parsed.first
        CpuStore.idle = parsed.second
        CpuStore.stampMs = System.currentTimeMillis()
        return parsed
    }

    /**
     * CPU usage over the window since the previous sample. When there is no
     * usable previous sample (cold process or stale store), it takes one,
     * waits [settleMs] and measures over that short window instead.
     * Returns -1 on failure.
     */
    suspend fun cpuUsage(settleMs: Long = 350L): Int {
        cpuFromDaemon()?.let { return it }

        val prevTotal = CpuStore.total
        val prevIdle = CpuStore.idle
        val age = System.currentTimeMillis() - CpuStore.stampMs

        if (prevTotal <= 0L || age !in 1..60_000L) {
            val first = sampleCpuTimes() ?: return -1
            delay(settleMs)
            val second = sampleCpuTimes() ?: return -1
            return usagePercent(first.first, first.second, second.first, second.second)
        }

        val now = sampleCpuTimes() ?: return -1
        return usagePercent(prevTotal, prevIdle, now.first, now.second)
    }

    /** Returns (used, total) RAM bytes; (0, 0) when unavailable. */
    fun readRam(context: Context): Pair<Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L to 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val total = info.totalMem
        if (total <= 0L) return 0L to 0L
        val used = (total - info.availMem).coerceAtLeast(0L)
        return used to total
    }

    /** Battery percentage 0..100, or -1 when unavailable (notification). */
    fun readBatteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return -1
        val value = runCatching {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull() ?: return -1
        return if (value in 1..100) value else -1
    }

    /**
     * Battery |current| in microamps: daemon BATTERY_PING when connected
     * (vendor-unit heuristics, same source as the battery screen), else the
     * public BATTERY_PROPERTY_CURRENT_NOW. Returns -1 when unknown. Sign
     * conventions differ per vendor (charging may be negative), hence abs.
     */
    suspend fun readCurrentUA(context: Context): Long {
        if (isConnected) {
            runCatching {
                val response = DaemonClient.battery()
                if (response != null && response.optBoolean("present", true)) {
                    val ua = response.optLong("currentUA", -1L)
                    if (ua != -1L) return abs(ua)
                }
            }
        }

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return -1L
        val value = runCatching {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrNull() ?: return -1L
        if (value == 0 || value == Int.MIN_VALUE) return -1L
        return abs(value.toLong())
    }

    /** Compact current for the widget column: 1.23A above 1 A, else 850mA. */
    fun formatCurrent(ua: Long): String = when {
        ua >= 1_000_000L -> String.format(Locale.US, "%.2fA", ua / 1_000_000.0)
        else -> String.format(Locale.US, "%.0fmA", ua / 1000.0)
    }

    /** Compact byte size for the narrow widget column: 4.6G / 512M / 64K. */
    fun formatCompact(bytes: Long): String = when {
        bytes >= (1L shl 30) -> String.format(Locale.US, "%.1fG", bytes / 1073741824.0)
        bytes >= (1L shl 20) -> String.format(Locale.US, "%.0fM", bytes / 1048576.0)
        else -> String.format(Locale.US, "%dK", bytes / 1024L)
    }
}
