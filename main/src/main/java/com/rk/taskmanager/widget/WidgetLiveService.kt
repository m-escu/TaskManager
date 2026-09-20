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
import com.rk.commons.settings.Settings
import com.rk.commons.strings
import com.rk.taskmanager.MainActivity
import com.rk.taskmanager.R
import com.rk.taskmanager.daemon.isConnected
import com.rk.taskmanager.daemon.startDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Live monitor service. Pushes a fresh sample to every widget once per
 * second and mirrors CPU/RAM/current-drain/temperature into the foreground
 * notification.
 *
 * Two independent activation sources keep it alive, tracked separately:
 *  - the QS tile ([LiveTileService]) starts a LIVE session for 1 Hz widgets,
 *  - the "permanent notification" setting (Settings -> Widget) keeps the
 *    notification running on its own, independent of widgets and tile; it
 *    is restored after reboot by [LiveBootReceiver].
 *
 * The service stops when BOTH sources are gone; the no-widgets auto-stop
 * only applies to tile sessions, never to the permanent notification.
 *
 * Data sources: the daemon is attached on start when a working mode is
 * configured (grants are already persisted, so no prompt ever fires while
 * the screen is off); every collector falls back to public APIs, so the
 * widget stays alive even with no daemon at all.
 */
class WidgetLiveService : Service() {

    companion object {
        const val ACTION_START = "com.rk.taskmanager.widget.action.START_LIVE"
        const val ACTION_START_NOTIF = "com.rk.taskmanager.widget.action.START_NOTIF"
        const val ACTION_STOP_QS = "com.rk.taskmanager.widget.action.STOP_LIVE"
        const val ACTION_STOP_NOTIF = "com.rk.taskmanager.widget.action.STOP_NOTIF"

        private const val CHANNEL_ID = "live_monitor"
        private const val NOTIF_ID = 1001
        private const val INTERVAL_MS = 1_000L
        private const val TAG = "WidgetLiveService"

        /** Mirrored by the QS tile and the ephemeral widget renderer: true
         *  whenever the service is sampling (any activation source). */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** True only while a QS-tile live session is active (tile state). */
        @Volatile
        var liveSession: Boolean = false
            private set

        /**
         * Optimistically clears the tile-session flag so the tile reflects
         * "off" instantly; the service's stop handler confirms it (and the
         * state stays false even if the service keeps running for the
         * permanent notification).
         */
        fun requestStopTileSession() {
            liveSession = false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var loopJob: Job? = null

    // Last rendered values — re-pushed with live=false in onDestroy so the
    // LIVE badge disappears the moment the session ends, not at the next
    // ephemeral pass up to 30 minutes later.
    @Volatile private var lastCpu = -1
    @Volatile private var lastRamUsed = 0L
    @Volatile private var lastRamTotal = 0L
    @Volatile private var lastCurrentUA = -1L
    @Volatile private var lastTempTenthsC = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter foreground first: any startForegroundService() start
        // must reach startForeground() regardless of the action's outcome.
        startInForeground()

        when (intent?.action) {
            ACTION_STOP_QS -> {
                liveSession = false
                if (!Settings.permanentNotification) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }

            ACTION_STOP_NOTIF -> {
                if (!liveSession) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }

            ACTION_START_NOTIF -> {
                // Settings-driven session: the pref is the source of truth.
            }

            else -> {
                // ACTION_START from the QS tile...
                if (intent?.action == ACTION_START) {
                    liveSession = true
                } else {
                    // ...or a sticky restart with a null intent. Nothing
                    // restores a tile session across process death; only
                    // the permanent notification comes back.
                    if (!Settings.permanentNotification) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
            }
        }

        isRunning = true
        ensureLoop()
        attachDaemon()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        liveSession = false
        runCatching {
            WidgetRenderer.push(
                context = this,
                cpuPercent = lastCpu,
                ramUsed = lastRamUsed,
                ramTotal = lastRamTotal,
                currentUA = lastCurrentUA,
                tempTenthsC = lastTempTenthsC,
                live = false,
            )
        }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Fire-and-forget daemon attach: gives live mode exact CPU/battery
     * readings whenever the configured working mode (ROOT/Shizuku) can be
     * satisfied silently. Failures are fine — every collector has a
     * public-API fallback.
     */
    private fun attachDaemon() {
        scope.launch {
            runCatching {
                if (!isConnected) {
                    startDaemon(this@WidgetLiveService, Settings.workingMode)
                }
            }
        }
    }

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            var tick = 0
            while (isActive) {
                try {
                    val cpu = WidgetStats.cpuUsage()
                    val (used, total) = WidgetStats.readRam(this@WidgetLiveService)
                    val battery = WidgetStats.readBattery(this@WidgetLiveService)

                    WidgetRenderer.push(
                        context = this@WidgetLiveService,
                        cpuPercent = cpu,
                        ramUsed = used,
                        ramTotal = total,
                        currentUA = battery.currentUA,
                        tempTenthsC = battery.tempTenthsC,
                        live = true,
                    )
                    lastCpu = cpu
                    lastRamUsed = used
                    lastRamTotal = total
                    lastCurrentUA = battery.currentUA
                    lastTempTenthsC = battery.tempTenthsC

                    // Re-read every tick: a changed setting applies live,
                    // without restarting the service. The first tick always
                    // updates (0 % n == 0) so the text is never stale.
                    val notifEvery = Settings.notifRefreshSeconds.coerceIn(1, 3600)
                    if (tick % notifEvery == 0) {
                        updateNotification(
                            cpu = cpu,
                            ramUsed = used,
                            ramTotal = total,
                            currentUA = battery.currentUA,
                            tempTenthsC = battery.tempTenthsC,
                        )
                    }
                    tick++

                    // Auto-stop when the user removed every widget instance —
                    // but never while the permanent notification is wanted.
                    if (placedWidgetIds().isEmpty() && !Settings.permanentNotification) {
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

        val notification = buildNotification(
            cpu = -1, ramUsed = 0L, ramTotal = 0L, currentUA = -1L, tempTenthsC = -1
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // specialUse is unknown to <34; passing it there would crash.
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(
        cpu: Int,
        ramUsed: Long,
        ramTotal: Long,
        currentUA: Long,
        tempTenthsC: Int,
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(cpu, ramUsed, ramTotal, currentUA, tempTenthsC))
    }

    private fun buildNotification(
        cpu: Int,
        ramUsed: Long,
        ramTotal: Long,
        currentUA: Long,
        tempTenthsC: Int,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ramPct = if (ramTotal > 0L) ((ramUsed * 100L) / ramTotal).toInt() else -1
        // Same columns the widget shows: compact signed drain ("+850mA" /
        // "-1.23A") and temperature ("32.4°C"), or the en-dash placeholder.
        val drain = if (currentUA != -1L) {
            WidgetStats.formatCurrent(currentUA)
        } else {
            getString(strings.widget_no_data)
        }
        val temp = if (tempTenthsC != -1) {
            WidgetStats.formatTemperature(tempTenthsC)
        } else {
            getString(strings.widget_no_data)
        }
        val text = getString(
            strings.live_notif_text,
            cpu.coerceAtLeast(0),
            ramPct.coerceAtLeast(0),
            drain,
            temp,
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
