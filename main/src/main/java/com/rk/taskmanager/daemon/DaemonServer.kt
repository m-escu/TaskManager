package com.rk.taskmanager.daemon

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONException
import org.json.JSONObject

val daemon_messages = DaemonServer.received_messages.asSharedFlow()
val send_daemon_messages = MutableSharedFlow<String>(extraBufferCapacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)
var isConnected by mutableStateOf(false)
    private set

/**
 * Highest daemon protocol version this build of the app understands. The
 * daemon announces its own version in HELLO; the connection is accepted when
 * the daemon's version is <= [MAX_SUPPORTED_PROTOCOL] (older daemons are
 * tolerated so the app can still talk to a daemon that only speaks v1,
 * newer ones are rejected with a clear error instead of cryptic failures).
 */
const val MAX_SUPPORTED_PROTOCOL = 2

var daemonCaps: Set<String> = emptySet()
    private set
var daemonProtocolVersion: Int = 0
    private set
var daemonVersionString: String = ""
    private set

object DaemonServer {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val received_messages =
        MutableSharedFlow<String>(extraBufferCapacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var readerJob: Job? = null
    private var writerJob: Job? = null

    /** Latest error that caused a connection attempt to fail, if any. */
    var lastError: String? = null
        private set

    // Protocol v2 request/response correlation: responses carrying an "id"
    // complete the pending request with that id. This replaces the fragile
    // "match responses by type" pattern for anything that needs an answer,
    // and works correctly with concurrent in-flight requests.
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val nextRequestId = AtomicLong(1)

    // Signals the first HELLO message of a connection attempt.
    private var helloSignal = CompletableDeferred<JSONObject>()

    private fun log(msg: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        Log.d("DaemonServer", "[$ts] $msg")
        println("[$ts] [DaemonServer] $msg")
    }

    /**
     * Starts daemon I/O and performs the protocol version handshake: blocks
     * until the daemon's HELLO arrives (max 5s), validates the protocol
     * version, and only then reports the connection as ready.
     */
    suspend fun start(input: InputStream, output: OutputStream): Boolean {
        if (readerJob?.isActive == true) {
            log("Daemon already running, ignoring start request")
            return true
        }

        // Fresh state for a fresh connection attempt.
        failAllPending("new connection attempt")
        lastError = null
        helloSignal = CompletableDeferred()

        startHandling(input, output)

        val hello = withTimeoutOrNull(5_000) {
            try {
                helloSignal.await()
            } catch (e: Exception) {
                log("Hello wait failed: ${e.message}")
                null
            }
        }

        if (hello == null) {
            lastError = "Daemon did not announce a protocol version (timeout)"
            stop()
            return false
        }

        val proto = hello.optInt("proto", 1)
        if (proto > MAX_SUPPORTED_PROTOCOL) {
            lastError = "Daemon protocol v$proto is newer than this app supports (v$MAX_SUPPORTED_PROTOCOL). Update the app."
            stop()
            return false
        }

        daemonProtocolVersion = proto
        daemonVersionString = hello.optString("version", "unknown")
        daemonCaps = hello.optJSONArray("caps")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.toSet()
        } ?: emptySet()

        log("Daemon v$daemonVersionString | protocol v$proto | caps: $daemonCaps")
        isConnected = true
        return true
    }

    private fun startHandling(input: InputStream, output: OutputStream) {
        readerJob = scope.launch {
            log("Reader started")
            try {
                val reader = input.bufferedReader()
                while (isActive) {
                    val message = reader.readLine() ?: break
                    if (message.isNotEmpty()) {
                        onMessage(message.trim())
                    }
                }
            } catch (e: IOException) {
                log("Reader error: ${e.message}")
                e.printStackTrace()
            } finally {
                isConnected = false
                failAllPending("daemon connection closed")
                log("Reader terminated")
            }
        }

        writerJob = scope.launch {
            try {
                send_daemon_messages.collect { message ->
                    withContext(Dispatchers.IO) {
                        output.write("$message\n".toByteArray())
                        output.flush()
                    }
                }
            } catch (e: IOException) {
                log("Writer error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun onMessage(message: String) {
        try {
            val json = JSONObject(message)
            if (json.has("id")) {
                val id = json.getString("id")
                pendingRequests.remove(id)?.complete(json)
            }
            if (json.optString("type") == "HELLO") {
                helloSignal.complete(json)
            }
        } catch (_: JSONException) {
            // Not valid JSON — still forward it below for legacy consumers.
        }
        // Every message is also emitted to the shared flow so existing
        // type-based consumers (graphs, process list, temps) keep working.
        received_messages.tryEmit(message)
    }

    /**
     * Sends [cmd] and suspends until the daemon's response with the same "id"
     * arrives (or [timeoutMs] elapses). Returns null on timeout/connection loss.
     */
    suspend fun request(cmd: JSONObject, timeoutMs: Long = 5_000): JSONObject? {
        if (!isConnected) return null
        val id = nextRequestId.getAndIncrement().toString()
        cmd.put("id", id)
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[id] = deferred
        return try {
            send_daemon_messages.emit(cmd.toString())
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            log("request(${cmd.optString("cmd")}) failed: ${e.message}")
            null
        } finally {
            pendingRequests.remove(id)
        }
    }

    private fun failAllPending(reason: String) {
        for ((_, deferred) in pendingRequests) {
            deferred.cancel()
        }
        pendingRequests.clear()
        helloSignal.completeExceptionally(IOException(reason))
    }

    suspend fun stop() {
        log("Stopping daemon I/O...")
        isConnected = false
        readerJob?.cancelAndJoin()
        readerJob = null
        writerJob?.cancelAndJoin()
        writerJob = null
        log("Daemon I/O stopped")
    }
}
