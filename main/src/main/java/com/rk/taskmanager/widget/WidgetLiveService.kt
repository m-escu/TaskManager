package com.rk.taskmanager.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rk.commons.strings
import com.rk.taskmanager.MainActivity
import com.rk.taskmanager.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Live widget mode: pushes a fresh sample to every widget once per second
 * and mirrors the same numbers into the foreground notification, so the
 * session is visible and cancellable like any other media-style FGS.
 *
 * Toggled by the QS tile ([LiveTileService]); also stops itself
 * automatically when the last widget instance is removed from home.
 *
 * Deliberately daemon-free: CPU/RAM/battery all come from public APIs
 * ([WidgetStats]), so live mode works regardless of working mode and never
 * pops a su/Shizuku prompt while the screen is off.
 */
class WidgetLiveService : Service() {

    companion object {
        const val ACTION_START = "com.rk.taskmanager.widget.action.START_LIVE"
        const val ACTION_STOP = "com.rk.taskmanager.widget.action.STOP_LIVE"

        private const val CHANNEL_ID = "live_monitor"
        private const val NOTIF_ID = 1001
        private const val INTERVAL_MS = 1_000L
        private const val NOTIF_UPDATE_EVERY_TICKS = 3
        private const val TAG = "WidgetLiveService"

        /** Mirrored by the QS tile and the ephemeral widget renderer. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()
        isRunning = true
        ensureLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            var tick = 0
            while (isActive) {
                try {
                    val cpu = WidgetStats.cpuUsage()
                    val (used, total) = WidgetStats.readRam(this@WidgetLiveService)
                    val batt = WidgetStats.readBatteryPercent(this@WidgetLiveService)

                    WidgetRenderer.push(
                        context = this@WidgetLiveService,
                        cpuPercent = cpu,
                        ramUsed = used,
                        ramTotal = total,
                        batteryPercent = batt,
                        live = true,
                    )

                    if (tick % NOTIF_UPDATE_EVERY_TICKS == 0) {
                        updateNotification(cpu, used, total, batt)
                    }
                    tick++

                    // Auto-stop when the user removed every widget instance.
                    if (placedWidgetIds().isEmpty()) {
                        Log.i(TAG, "no widget instances left; stopping live mode")
                        stopSelf()
                        break
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "live tick failed", t)
                }
                delay(INTERVAL_MS)
            }
        }
    }

    private fun placedWidgetIds(): IntArray {
        val manager = AppWidgetManager.getInstance(this) ?: return IntArray(0)
        return manager.getAppWidgetIds(
            ComponentName(this, TaskManagerWidgetProvider::class.java)
        ) ?: IntArray(0)
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(strings.live_notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )

        val notification = buildNotification(cpu = -1, ramUsed = 0L, ramTotal = 0L, batt = -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // specialUse is unknown to <34; passing it there would crash.
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(cpu: Int, ramUsed: Long, ramTotal: Long, batt: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(cpu, ramUsed, ramTotal, batt))
    }

    private fun buildNotification(cpu: Int, ramUsed: Long, ramTotal: Long, batt: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ramPct = if (ramTotal > 0L) ((ramUsed * 100L) / ramTotal).toInt() else -1
        val text = getString(
            strings.live_notif_text,
            cpu.coerceAtLeast(0),
            ramPct.coerceAtLeast(0),
            batt.coerceAtLeast(0),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_taskmanager_foreground)
            .setContentTitle(getString(strings.live_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }
}
