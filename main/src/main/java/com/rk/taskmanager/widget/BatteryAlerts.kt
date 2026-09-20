package com.rk.taskmanager.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.rk.commons.settings.Settings
import com.rk.commons.strings
import com.rk.taskmanager.MainActivity
import com.rk.taskmanager.R
import java.util.Locale
import kotlin.math.abs

/**
 * Threshold alerts for the battery, evaluated by the live monitor service
 * on every 1 Hz tick (fork15). Two independent checks:
 *
 *  - drain: the SIGNED current ([WidgetStats.readBattery] already
 *    normalizes vendor conventions to negative = discharging) is only
 *    meaningful while DISCHARGING — charging at +2 A is fine, discharging
 *    at 2 A is what the user wants to know about. The magnitude must stay
 *    at or above the configured mA threshold for the configured duration.
 *  - temperature: tenths-of-°C reading at or above the configured °C
 *    threshold for the configured duration.
 *
 * Delivery per [Settings.batteryAlertDelivery]: a notification on its own
 * channel, a toast, or both. The alert fires once when the sustained
 * condition becomes true and repeats every [REPEAT_MS] while it persists;
 * the episode tracking resets as soon as the reading drops below the
 * threshold (or becomes unavailable). Everything lives in memory: a service
 * restart re-arms the trackers, which at worst delays a repeat by one
 * sustain period.
 */
object BatteryAlerts {

    private const val CHANNEL_ID = "battery_alerts"
    private const val NOTIF_ID = 1002

    /** Re-fire interval while the condition stays continuously elevated. */
    private const val REPEAT_MS = 10L * 60 * 1000

    /** Sustain bookkeeping for one metric: 0 = not currently elevated. */
    private class Tracker {
        var sinceMs = 0L
        var lastAlertMs = 0L
    }

    private val drainTracker = Tracker()
    private val tempTracker = Tracker()

    fun evaluate(context: Context, sample: WidgetStats.BatterySample) {
        if (!Settings.batteryAlerts) return
        val now = System.currentTimeMillis()

        // Drain: only a NEGATIVE (discharging) reading counts. -1 = no
        // reading -> treat as not elevated so trackers reset cleanly.
        val drainMa = if (sample.currentUA < 0) (abs(sample.currentUA) / 1000L).toInt() else 0
        val drainThreshold = Settings.batteryDrainMa.coerceAtLeast(1)
        val drainElevated = sample.currentUA != -1L && drainMa >= drainThreshold
        evaluateMetric(
            context = context,
            tracker = drainTracker,
            elevated = drainElevated,
            sustainMs = Settings.batteryDrainSustainSec.coerceIn(5, 86_400) * 1000L,
            now = now,
            valueText = WidgetStats.formatCurrent(sample.currentUA),
            thresholdText = String.format(Locale.US, "%d mA", drainThreshold),
            textRes = strings.batt_alert_drain,
        )

        val tempC = sample.tempTenthsC
        val tempThreshold = Settings.batteryTempC.coerceAtLeast(1)
        val tempElevated = tempC != -1 && (tempC.toFloat() / 10f) >= tempThreshold
        evaluateMetric(
            context = context,
            tracker = tempTracker,
            elevated = tempElevated,
            sustainMs = Settings.batteryTempSustainSec.coerceIn(5, 86_400) * 1000L,
            now = now,
            valueText = WidgetStats.formatTemperature(tempC),
            thresholdText = String.format(Locale.US, "%.0f °C", tempThreshold.toFloat()),
            textRes = strings.batt_alert_temp,
        )
    }

    private fun evaluateMetric(
        context: Context,
        tracker: Tracker,
        elevated: Boolean,
        sustainMs: Long,
        now: Long,
        valueText: String,
        thresholdText: String,
        textRes: Int,
    ) {
        if (!elevated) {
            tracker.sinceMs = 0L
            tracker.lastAlertMs = 0L
            return
        }
        if (tracker.sinceMs == 0L) {
            tracker.sinceMs = now
            return
        }
        val sustainedFor = now - tracker.sinceMs
        if (sustainedFor < sustainMs) return
        if (tracker.lastAlertMs != 0L && now - tracker.lastAlertMs < REPEAT_MS) return

        tracker.lastAlertMs = now
        val text = context.getString(textRes, valueText, thresholdText, formatSustain(sustainedFor))
        val delivery = Settings.batteryAlertDelivery
        if (delivery == 0 || delivery == 2) postNotification(context, text)
        if (delivery == 1 || delivery == 2) {
            // Toast.show() needs a Looper; the caller (the service loop) runs
            // on Dispatchers.Default, so hop to the main looper.
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** "45 s" under a minute, "3 min" under an hour, "1.5 h" beyond. */
    private fun formatSustain(ms: Long): String {
        val seconds = ms / 1000
        return when {
            seconds < 60 -> String.format(Locale.US, "%d s", seconds)
            seconds < 3600 -> String.format(Locale.US, "%d min", seconds / 60)
            else -> String.format(Locale.US, "%.1f h", seconds / 3600.0)
        }
    }

    private fun postNotification(context: Context, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(strings.batt_alert_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_taskmanager_foreground)
            .setContentTitle(context.getString(strings.batt_alert_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(NOTIF_ID, notification) }
    }
}
