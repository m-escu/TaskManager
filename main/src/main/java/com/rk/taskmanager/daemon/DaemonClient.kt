package com.rk.taskmanager.daemon

import android.util.Log
import com.rk.commons.settings.Settings
import com.rk.taskmanager.ProcessViewModel
import com.rk.taskmanager.TaskManager
import com.rk.taskmanager.screens.isAppInstalled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * How the Kill button stops a process.
 *
 * Maps to [Settings.defaultKillAction] (ids are stable persisted values).
 */
enum class KillAction(val id: Int) {
    /** UI-level sentinel: show the Terminate / Force-kill chooser dialog. */
    ASK(0),

    /** SIGTERM now; the daemon escalates to SIGKILL after the grace period. */
    TERMINATE(1),

    /** SIGKILL immediately. */
    FORCE(2);

    /** Resolves the UI sentinel to a concrete action (used when no prompt is shown). */
    fun resolve(): KillAction = if (this == ASK) TERMINATE else this

    companion object {
        fun fromId(id: Int): KillAction = entries.firstOrNull { it.id == id } ?: ASK
    }
}

// ---------------------------------------------------------------------------
// Pure helpers — unit-tested on the JVM (DaemonClientTest). Keep them free of
// any Android framework dependencies.
// ---------------------------------------------------------------------------

/** Parses the "caps" array of a HELLO message; tolerant of missing/invalid fields. */
fun parseCaps(json: JSONObject): Set<String> =
    json.optJSONArray("caps")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).takeIf { it.isNotEmpty() }
        }.toSet()
    } ?: emptySet()

/**
 * Builds the daemon request that stops a process, applying the fork's kill
 * policy:
 *
 *  - Installed Android apps are always stopped with `am force-stop`: it is
 *    the reliable way to end an app (services, alarms, recent-task entry all
 *    go away), and plain signals are often swallowed by the runtime.
 *  - Native processes: TERMINATE maps to KILL_GRACEFUL (SIGTERM, daemon
 *    escalates to SIGKILL after [graceMs]) when the daemon advertises the
 *    `kill_graceful` capability, falling back to plain SIGKILL on legacy
 *    daemons. FORCE always maps to the immediate-SIGKILL command.
 */
fun buildKillRequest(
    isInstalledApp: Boolean,
    packageName: String,
    pid: Int,
    action: KillAction,
    graceMs: Int,
    daemonSupportsGracefulKill: Boolean,
): JSONObject = JSONObject().apply {
    if (isInstalledApp) {
        put("cmd", "FORCE_STOP")
        put("pkg", packageName)
    } else if (action == KillAction.TERMINATE && daemonSupportsGracefulKill) {
        put("cmd", "KILL_GRACEFUL")
        put("pid", pid)
        // Must match the daemon's clamp range (taskmanagerd.cpp).
        put("timeoutMs", graceMs.coerceIn(200, 10_000))
    } else {
        put("cmd", "KILL")
        put("pid", pid)
    }
}

/**
 * Typed, request-id correlated command layer on top of [DaemonServer].
 *
 * Every call goes through [DaemonServer.request], so responses are matched
 * by their "id" (no cross-talk between concurrent callers, no racy
 * type-based message matching) and unknown/timeout answers surface as null.
 * Caps-based feature checks let Phase-4 screens degrade gracefully when an
 * older daemon is connected.
 */
object DaemonClient {

    /** True when the connected daemon only speaks protocol v1. */
    val isLegacyDaemon: Boolean get() = daemonProtocolVersion in 1 until MAX_SUPPORTED_PROTOCOL

    fun hasCap(cap: String): Boolean = cap in daemonCaps

    /**
     * Stops [proc] using the fork's kill policy (see [buildKillRequest]).
     * Returns true when the daemon confirmed the command; the counter in
     * [Settings.kills] is incremented only on confirmed successes.
     */
    suspend fun kill(proc: ProcessViewModel.Process, action: KillAction): Boolean =
        withContext(Dispatchers.IO) {
            val context = runCatching { TaskManager.requireContext() }.getOrNull()
            val isApp = context != null && isAppInstalled(context, proc.cmdLine)

            val request = buildKillRequest(
                isInstalledApp = isApp,
                packageName = proc.cmdLine,
                pid = proc.pid,
                action = action,
                graceMs = Settings.killGraceMs,
                daemonSupportsGracefulKill = hasCap("kill_graceful"),
            )

            val response = DaemonServer.request(request, timeoutMs = 3_000)
            val ok = response?.optBoolean("success", false) ?: false

            if (ok) {
                Settings.kills++
            } else {
                Log.w(
                    "DaemonClient",
                    "kill failed: cmd=${request.optString("cmd")} pid=${proc.pid} " +
                        "connected=$isConnected",
                )
            }
            ok
        }

    // -- Typed wrappers over the Phase-2 daemon commands (used by Phase-4 screens) --

    /** Total CPU usage 0..100 (`CPU_PING`, answered from the daemon's
     *  non-blocking sampler thread). */
    suspend fun cpu(timeoutMs: Long = 3_000): JSONObject? =
        DaemonServer.request(JSONObject().put("cmd", "CPU_PING"), timeoutMs)

    /** Per-core usage + frequencies (`CORE_PING`). */
    suspend fun cores(timeoutMs: Long = 5_000): JSONObject? =
        DaemonServer.request(JSONObject().put("cmd", "CORE_PING"), timeoutMs)

    /** Battery stats (`BATTERY_PING`). */
    suspend fun battery(timeoutMs: Long = 5_000): JSONObject? =
        DaemonServer.request(JSONObject().put("cmd", "BATTERY_PING"), timeoutMs)

    /** PSS memory breakdown for one pid (`PSS_PING`). */
    suspend fun pss(pid: Int, timeoutMs: Long = 5_000): JSONObject? =
        DaemonServer.request(JSONObject().put("cmd", "PSS_PING").put("pid", pid), timeoutMs)

    /** Push-mode subscription (`SUBSCRIBE`). Topics: cpu, battery, mem, net. */
    suspend fun subscribe(topics: List<String>, intervalMs: Long, timeoutMs: Long = 3_000): JSONObject? =
        DaemonServer.request(
            JSONObject()
                .put("cmd", "SUBSCRIBE")
                .put("topics", JSONArray(topics))
                .put("intervalMs", intervalMs),
            timeoutMs,
        )

    /** Stops push-mode frames (`UNSUBSCRIBE`). */
    suspend fun unsubscribe(timeoutMs: Long = 3_000): JSONObject? =
        DaemonServer.request(JSONObject().put("cmd", "UNSUBSCRIBE"), timeoutMs)
}
