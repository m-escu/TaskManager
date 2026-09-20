package com.rk.taskmanager.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.rk.commons.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * User-tunable ephemeral refresh (Settings -> Widget). The system's own
 * 30-minute APPWIDGET_UPDATE remains as the backstop; this inexact,
 * non-wakeup alarm makes faster intervals possible without any long-lived
 * process. No alarm is armed while zero widgets are placed, so it can never
 * wake the process for nothing.
 */
object WidgetRefreshScheduler {

    const val MIN_MINUTES = 15
    const val MAX_MINUTES = 720

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WidgetRefreshReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun hasWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        val ids = manager.getAppWidgetIds(
            ComponentName(context, TaskManagerWidgetProvider::class.java)
        )
        return ids != null && ids.isNotEmpty()
    }

    /** (Re-)arms the repeating alarm at the configured interval. */
    fun schedule(context: Context) {
        if (!hasWidgets(context)) return

        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context)
        pi.cancel()
        val intervalMs = Settings.widgetRefreshMinutes
            .coerceIn(MIN_MINUTES, MAX_MINUTES) * 60_000L
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + intervalMs,
            intervalMs,
            pi,
        )
    }

    /** Drops the alarm (last widget removed). */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        pendingIntent(context).cancel()
    }
}

/**
 * Fires the user-configured ephemeral refresh. Alarms do not survive
 * reboots, so every fire re-arms the next one; Application.onCreate and
 * the provider's onEnabled arm it again from the other side.
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetRefresher.refresh(context)
                WidgetRefreshScheduler.schedule(context)
            } catch (t: Throwable) {
                Log.w(TAG, "scheduled refresh failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "TaskManagerWidget"
    }
}
