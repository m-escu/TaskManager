package com.rk.taskmanager.daemon

import android.content.Context
import android.util.Log
import com.rk.commons.application
import com.rk.taskmanager.settings.WorkingMode
import com.rk.taskmanager.shizuku.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// Single-flight guard: concurrent callers (UI + widget + QS tile in the
// future) queue up on this mutex instead of racing on a boolean flag.
private val daemonStartMutex = Mutex()

suspend fun startDaemon(
    context: Context,
    mode: Int
): DaemonResult = daemonStartMutex.withLock {
    val daemonFile = File(application!!.applicationInfo.nativeLibraryDir, "libtaskmanagerd.so")
    withContext(Dispatchers.IO) {
        try {
            when (mode) {
                WorkingMode.SHIZUKU.id -> {
                    if (!ShizukuShell.isShizukuRunning()) {
                        return@withContext DaemonResult.SHIZUKU_NOT_RUNNING
                    }

                    if (!ShizukuShell.isPermissionGranted()) {
                        return@withContext DaemonResult.SHIZUKU_PERMISSION_DENIED
                    }

                    val process = ShizukuShell.startStreamingProcess(
                        cmd = arrayOf(daemonFile.absolutePath),
                        env = arrayOf(),
                        dir = "/"
                    )

                    val started = DaemonServer.start(process.inputStream, process.outputStream)
                    if (!started) {
                        return@withContext DaemonResult.DAEMON_REFUSED.also {
                            it.message = DaemonServer.lastError ?: "Failed to start daemon I/O"
                        }
                    }

                    DaemonResult.OK
                }

                WorkingMode.ROOT.id -> {
                    val suCheck = isSuWorking()

                    if (!suCheck.first) {
                        return@withContext DaemonResult.SU_FAILED.also {
                            it.message = suCheck.second?.message ?: "unknown error"
                        }
                    }

                    val cmd = arrayOf("su", "-c", daemonFile.absolutePath)
                    val processBuilder = ProcessBuilder(*cmd)
                    processBuilder.directory(File("/"))

                    val process = processBuilder.start()
                    val started = DaemonServer.start(process.inputStream, process.outputStream)
                    if (!started) {
                        return@withContext DaemonResult.DAEMON_REFUSED.also {
                            it.message = DaemonServer.lastError ?: "Failed to start daemon I/O"
                        }
                    }

                    DaemonResult.OK
                }

                WorkingMode.NOT_SET.id -> {
                    DaemonResult.SKIPPED
                }

                else -> {
                    Log.e("startDaemon", "Unknown working mode $mode")
                    DaemonResult.SKIPPED
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            DaemonResult.UNKNOWN_ERROR.also {
                it.message = e.message
            }
        }
    }
}

suspend fun isSuWorking(): Pair<Boolean, Exception?> = withContext(Dispatchers.IO) {
    try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
        val output = process.inputStream.bufferedReader().readLine()
        process.waitFor()
        Pair(output == "0", null)
    } catch (e: Exception) {
        e.printStackTrace()
        Pair(false, e)
    }
}
