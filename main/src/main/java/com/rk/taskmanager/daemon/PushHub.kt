package com.rk.taskmanager.daemon

import android.util.Log
import com.rk.commons.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Owns the daemon's single push subscription (SUBSCRIBE: cpu, mem, battery,
 * net) and fans the PUSH frames out to every screen.
 *
 * Before this, every live graph polled its own request every tick, and the
 * graph pinger ran from GlobalScope forever — including with the app in the
 * background. Now:
 *  - the subscription exists only while the UI is in the foreground
 *    ([setForeground], driven by MainActivity onStart/onStop),
 *  - its interval follows Settings -> Graph -> update delay,
 *  - it is re-established after a daemon reconnect ([DaemonServer.connectionId]),
 *  - screens use [live], which consumes push frames while they flow and
 *    falls back to polling otherwise (old daemon, no push cap, push stalled).
 */
object PushHub {
    private const val TAG = "PushHub"
    val TOPICS = listOf("cpu", "mem", "battery", "net")

    /** Daemon clamp range for SUBSCRIBE intervalMs. */
    private const val MIN_INTERVAL_MS = 250L
    private const val MAX_INTERVAL_MS = 5_000L

    data class Frame(val topic: String, val data: JSONObject)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frames = MutableSharedFlow<Frame>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile
    private var foreground = false

    private val foregroundState = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * Suspends while the UI is in the background. Compose effects keep
     * running when the activity is stopped, so polling loops call this
     * before each round instead of hitting the daemon for an invisible UI.
     */
    suspend fun awaitForeground() {
        foregroundState.first { it }
    }

    /** True while the UI is visible (MainActivity between onStart and onStop). */
    val isForeground: Boolean get() = foreground

    /** Interval of the live subscription, 0 = none. */
    @Volatile
    private var subscribedIntervalMs = 0L

    @Volatile
    private var subscribedConnection = -1L

    @Volatile
    private var lastFrameAtMs = 0L

    private var job: Job? = null

    /** True while frames are actually arriving (subscribed and not stalled). */
    val isServing: Boolean
        get() = subscribedIntervalMs > 0L &&
            subscribedConnection == DaemonServer.connectionId &&
            isConnected &&
            System.currentTimeMillis() - lastFrameAtMs < subscribedIntervalMs * 4 + 1_000

    fun wantedIntervalMs(): Long =
        Settings.updateFrequency.toLong().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    fun setForeground(value: Boolean) {
        foreground = value
        foregroundState.value = value
        ensureStarted()
    }

    @Synchronized
    private fun ensureStarted() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch {
                daemon_messages.collect { msg ->
                    // Cheap pre-filter: most traffic is not a push frame.
                    if (!msg.contains("\"PUSH\"")) return@collect
                    runCatching {
                        val j = JSONObject(msg)
                        if (j.optString("type") == "PUSH") {
                            val data = j.optJSONObject("data") ?: return@runCatching
                            lastFrameAtMs = System.currentTimeMillis()
                            frames.tryEmit(Frame(j.optString("topic"), data))
                        }
                    }
                }
            }
            while (isActive) {
                runCatching { reconcile() }.onFailure { Log.w(TAG, "reconcile failed", it) }
                delay(500)
            }
        }
    }

    private suspend fun reconcile() {
        val conn = DaemonServer.connectionId
        val supported = isConnected && DaemonClient.hasCap("push_subscribe")
        val want = if (foreground && supported) wantedIntervalMs() else 0L

        // A reconnect drops the daemon-side subscription silently.
        if (subscribedConnection != conn) subscribedIntervalMs = 0L

        if (want == subscribedIntervalMs) return
        if (want == 0L) {
            if (isConnected && subscribedIntervalMs > 0L) DaemonClient.unsubscribe()
            subscribedIntervalMs = 0L
            return
        }
        val ack = DaemonClient.subscribe(TOPICS, want)
        if (ack?.optString("type") == "SUBSCRIBED") {
            subscribedIntervalMs = want
            subscribedConnection = conn
            lastFrameAtMs = System.currentTimeMillis()
            Log.i(TAG, "subscribed ${TOPICS.joinToString()} every ${want}ms")
        } else {
            subscribedIntervalMs = 0L
        }
    }

    /** Raw frames of one topic. */
    fun topic(name: String): Flow<JSONObject> = frames.filter { it.topic == name }.map { it.data }

    /**
     * Live values for [topic]: push frames while [isServing], otherwise
     * [poll] every [pollMs]; nothing at all while the UI is in the
     * background. Emits null when a poll fails, like the old
     * request() loops did, so callers keep their existing null handling.
     */
    fun live(topic: String, pollMs: () -> Long, poll: suspend () -> JSONObject?): Flow<JSONObject?> =
        channelFlow {
            while (isActive) {
                if (!foreground) {
                    // Compose effects keep running while the activity is
                    // stopped; don't poll for a UI nobody can see.
                    delay(500)
                } else if (isServing) {
                    val frame = withTimeoutOrNull(subscribedIntervalMs * 3 + 500) { topic(topic).first() }
                    if (frame != null) send(frame)
                } else {
                    send(poll())
                    delay(pollMs())
                }
            }
        }
}
