#!/usr/bin/env python3
"""Integration tests for taskmanagerd (protocol v2).

Builds nothing itself; run taskmanagerd/host_tests/build_host.sh first.
Spawns the host-built daemon binary and exercises the wire protocol against
the real /proc of the machine (works locally and on GitHub Actions runners).

Exit code 0 = all tests passed.
"""
import json
import os
import queue
import signal
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
BIN = os.path.join(HERE, "build", "taskmanagerd_host")


class Daemon:
    def __init__(self):
        self.proc = subprocess.Popen(
            [BIN], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, text=True, bufsize=1,
        )
        self.lines = queue.Queue()
        self.reader = threading.Thread(target=self._read_loop, daemon=True)
        self.reader.start()

    def _read_loop(self):
        try:
            for line in self.proc.stdout:
                self.lines.put(line.rstrip("\n"))
        except Exception:
            pass

    def send(self, obj):
        self.proc.stdin.write(json.dumps(obj) + "\n")
        self.proc.stdin.flush()

    def send_raw(self, raw):
        self.proc.stdin.write(raw + "\n")
        self.proc.stdin.flush()

    def recv(self, timeout=5.0):
        return json.loads(self.lines.get(timeout=timeout))

    def request(self, obj, timeout=5.0):
        self.send(obj)
        return self.recv(timeout)

    def close_stdin(self):
        try:
            self.proc.stdin.close()
        except Exception:
            pass

    def wait(self, timeout=5.0):
        return self.proc.wait(timeout=timeout)

    def kill(self):
        self.proc.kill()


passed = 0
failed = 0


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS {name}")
    else:
        failed += 1
        print(f"  FAIL {name}  {detail}")


def main():
    if not os.path.exists(BIN):
        print(f"daemon binary not found at {BIN}; run build_host.sh first")
        sys.exit(2)

    d = Daemon()

    # --- 1. HELLO announced immediately on startup -------------------------
    hello = d.recv()
    check("hello_on_start",
          hello.get("type") == "HELLO" and hello.get("proto", 0) >= 2, str(hello))
    check("hello_caps",
          isinstance(hello.get("caps"), list) and len(hello["caps"]) > 0, str(hello))
    check("hello_version", bool(hello.get("version")), str(hello))

    # --- 2. PING echoes request id ----------------------------------------
    r = d.request({"cmd": "PING", "id": 42})
    check("ping_id_echo", r.get("type") == "PONG" and r.get("id") == 42, str(r))

    # --- 3. HELLO as a command (re-handshake) ------------------------------
    r = d.request({"cmd": "HELLO", "id": 1})
    check("hello_cmd", r.get("type") == "HELLO" and r.get("proto", 0) >= 2 and r.get("id") == 1, str(r))

    # --- 4. LIST_PROCESS shape ---------------------------------------------
    r = d.request({"cmd": "LIST_PROCESS"})
    procs = r.get("processes", [])
    check("list_shape",
          len(procs) > 0 and all(
              "pid" in p and "name" in p and "cpuUsage" in p and "memoryUsageKb" in p
              for p in procs), f"n={len(procs)}")
    check("cpuTimeTicks_field", all("cpuTimeTicks" in p for p in procs))
    check("self_in_list", any(p["pid"] == d.proc.pid for p in procs))

    # --- 5. Windowed per-process CPU ---------------------------------------
    busy = subprocess.Popen(["sh", "-c", "while :; do :; done"])
    time.sleep(0.1)
    try:
        d.request({"cmd": "LIST_PROCESS"})  # primes the per-pid sample window
        time.sleep(0.6)
        r = d.request({"cmd": "LIST_PROCESS"})
        busy_row = next((p for p in r["processes"] if p["pid"] == busy.pid), None)
        check("windowed_cpu_busy",
              busy_row is not None and busy_row["cpuUsage"] > 1.0, str(busy_row))
    finally:
        busy.kill()
        busy.wait()

    # --- 5b. Windowed CPU is NOT the lifetime average -----------------------
    # A process that burns CPU then freezes must report ~0% on the next window.
    # The old lifetime-average implementation would report ~50% here.
    p = subprocess.Popen(["sh", "-c", "while :; do :; done"])
    time.sleep(0.1)
    try:
        d.request({"cmd": "LIST_PROCESS"})   # t0: prime
        time.sleep(0.5)                      # process burns CPU for ~0.5s
        d.request({"cmd": "LIST_PROCESS"})   # t0.5: busy window
        p.send_signal(signal.SIGSTOP)        # freeze: no more CPU time
        time.sleep(0.5)
        r = d.request({"cmd": "LIST_PROCESS"})  # t1.0: window t0.5->t1.0
        row = next((x for x in r["processes"] if x["pid"] == p.pid), None)
        check("windowed_cpu_zero_after_stop",
              row is not None and row["cpuUsage"] < 10.0, str(row))
    finally:
        p.kill()
        p.wait()

    # --- 6. CPU_PING served from sampler -----------------------------------
    r = d.request({"cmd": "CPU_PING"})
    usage = r.get("usage", -1)
    check("cpu_ping_range", 0 <= usage <= 100, str(r))

    # --- 7. SWAP_PING -------------------------------------------------------
    r = d.request({"cmd": "SWAP_PING"})
    check("swap_shape", r.get("used", -1) >= 0 and r.get("total", -1) >= 0, str(r))

    # --- 8. PING_PID_CPU (first sight -> lifetime average >= 0) -------------
    r = d.request({"cmd": "PING_PID_CPU", "pid": os.getpid()})
    check("pid_cpu", r.get("type") == "PROCESS_CPU_USAGE" and r.get("usage", -1) >= 0, str(r))

    # --- 9. malformed JSON does not crash the daemon ------------------------
    d.send_raw("this is not json {")
    time.sleep(0.2)
    r = d.request({"cmd": "PING", "id": 99})
    check("survives_garbage", r.get("type") == "PONG", str(r))

    # --- 10. unknown command does not crash the daemon ----------------------
    d.send({"cmd": "DEFINITELY_NOT_A_CMD"})
    r = d.request({"cmd": "PING", "id": 100})
    check("survives_unknown", r.get("type") == "PONG", str(r))

    # --- 11. KILL ------------------------------------------------------------
    victim = subprocess.Popen(["sleep", "300"])
    time.sleep(0.1)
    r = d.request({"cmd": "KILL", "pid": victim.pid})
    victim.wait(timeout=5)
    check("kill_works", r.get("success") is True, str(r))

    # --- 12. FORCE_STOP rejects shell metacharacters -------------------------
    r = d.request({"cmd": "FORCE_STOP", "pkg": "bad;package"})
    check("force_stop_validated", r.get("success") is False, str(r))

    # --- 13. STOP_SELF terminates the daemon cleanly -------------------------
    d.send({"cmd": "STOP_SELF"})
    d.close_stdin()
    rc = d.wait(timeout=5)
    check("stop_self", rc == 0, f"rc={rc}")

    print(f"\n{passed} passed, {failed} failed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
