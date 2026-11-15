package com.rk.taskmanager.daemon

import com.rk.taskmanager.MainActivity
import com.rk.taskmanager.navControllerRef
import com.rk.taskmanager.screens.cpu.updateCpuGraph
import com.rk.taskmanager.screens.gpu.updateGpuGraph
import com.rk.taskmanager.screens.ram.updateRamAndSwapGraph
import com.rk.taskmanager.screens.selectedscreen
import com.rk.commons.settings.Settings
import com.rk.taskmanager.settings.SettingsRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

// MainActivity.onCreate runs again on every recreate (rotation, theme
// change); the whole updater must start exactly once per process, or each
// recreate adds another collector and every graph gets duplicate points.
private val updaterStarted = AtomicBoolean(false)

private suspend fun updateSwap(used: Long, total: Long) {
    val safeTotal = if (total > 0L) total else 1L
    val percentage = (used.toFloat() / safeTotal.toFloat()) * 100
    updateRamAndSwapGraph(percentage.toInt(), used, total)
}

@Suppress("UNUSED_PARAMETER")
suspend fun CoroutineScope.graphUpdater(activity: MainActivity) {
    if (!updaterStarted.compareAndSet(false, true)) return
    val graphMutex = Mutex()

    // Polled answers (legacy path, and GPU which has no push topic).
    launch(Dispatchers.Default) {
        daemon_messages.collect { message ->
            try {
                val json = JSONObject(message)
                graphMutex.withLock {
                    when (json.optString("type")) {
                        "CPU_USAGE" -> updateCpuGraph(json.optInt("usage"))
                        "GPU_USAGE" -> updateGpuGraph(json.optInt("usage"))
                        "SWAP_USAGE" -> updateSwap(json.optLong("used", 0L), json.optLong("total", 1L))
                    }
                }
            } catch (e: Exception) {
                // Ignore non-graph messages
            }
        }
    }

    // Push frames: CPU and swap arrive on the PushHub subscription.
    launch(Dispatchers.Default) {
        PushHub.topic("cpu").collect { data ->
            val usage = data.optInt("usage", -1)
            if (usage >= 0) graphMutex.withLock { updateCpuGraph(usage) }
        }
    }
    launch(Dispatchers.Default) {
        PushHub.topic("mem").collect { data ->
            val totalKb = data.optLong("swapTotalKb", -1L)
            val usedKb = data.optLong("swapUsedKb", -1L)
            if (totalKb >= 0L && usedKb >= 0L) {
                graphMutex.withLock { updateSwap(usedKb * 1024L, totalKb * 1024L) }
            }
        }
    }

    launch(Dispatchers.IO) {
        while (isActive) {
            // Nothing to draw in the background: pinging forever from
            // GlobalScope was a steady battery cost with the app closed.
            if (isConnected && PushHub.isForeground) {
                graphMutex.withLock {
                    if (!PushHub.isServing) {
                        send_daemon_messages.emit(JSONObject().apply { put("cmd", "CPU_PING") }.toString())
                        delay(16.milliseconds)
                        send_daemon_messages.emit(JSONObject().apply { put("cmd", "SWAP_PING") }.toString())
                        delay(15.milliseconds)
                    }
                    send_daemon_messages.emit(JSONObject().apply { put("cmd", "GPU_PING") }.toString())
                }
            }
            val delayMs = if (selectedscreen.intValue == 0 && navControllerRef.get()?.currentDestination?.route == SettingsRoutes.Home.route) {
                Settings.updateFrequency.toLong()
            } else {
                Settings.updateFrequency.toLong() * 2
            }
            delay(delayMs.milliseconds)
        }
    }
}
