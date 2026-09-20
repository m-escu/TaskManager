package com.rk.taskmanager.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.rk.commons.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * User-tunable ephemeral refresh (Settings -> Widget), free-form input from
 * 5 seconds to 24 hours.
 *
 * Why exact one-shots instead of setInexactRepeating: batched inexact
 * repeating alarms are advisory — the system (and OEM power handlers) fold
 * them into shared alarm windows and defer them arbitrarily, so a chosen
 * interval simply never materialized in the field. Instead we self-reschedule
 * a single exact alarm on every fire: the timing is honored, the chain
 * survives process death (Application.onCreate re-arms) and reboots (the
 * 30-minute system cadence + process start heal it), and nothing persists
 * between fires. When exact alarms are not permitted (SCHEDULE_EXACT_ALARM
 * is denied by default on Android 14+), we fall back to inexact one-shots,
 * which are still far better behaved than a repeating inexact alarm.
 *
 * No alarm is armed while zero widgets are placed, so it can never wake the
 * process for nothing. The system's own 30-minute APPWIDGET_UPDATE remains
 * as the backstop either way.
 */
object WidgetRefreshScheduler {

    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 86_400
    const val ACTION_REFRESH = "com.rk.taskmanager.widget.action.REFRESH"

    /** Clamped interval actually used by the scheduler, in seconds. */
    fun intervalSeconds(): Int = Settings.widgetRefreshSeconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, WidgetRefreshReceiver::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun hasWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        val ids = manager.getAppWidgetIds(
            ComponentName(context, TaskManagerWidgetProvider::class.java)
        )
        return ids != null && ids.isNotEmpty()
    }

    /** (Re-)arms the one-shot alarm at the configured interval. */
    fun schedule(context: Context) {
        if (!hasWidgets(context)) return

        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context)
        // Drop any previously-armed alarm for this intent BEFORE re-arming.
        // This MUST be am.cancel(pi), NOT pi.cancel(): PendingIntent.cancel()
        // invalidates the PendingIntent itself, and an alarm registered
        // afterwards with a cancelled PI is silently dropped on delivery —
        // the whole refresh chain never fired in the field. AlarmManager
        // .cancel removes pending alarms while keeping the PI usable.
        am.cancel(pi)
        val intervalMs = intervalSeconds() * 1000L
        val triggerAt = SystemClock.elapsedRealtime() + intervalMs
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, triggerAt, pi)
        } else {
            am.set(AlarmManager.ELAPSED_REALTIME, triggerAt, pi)
        }
    }

    /** Drops the alarm (last widget removed). */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        pendingIntent(context).cancel()
    }
}

/**
 * Fires the user-configured ephemeral refresh, then arms the next shot.
 * Alarms do not survive reboots, so every fire re-arms; Application.onCreate,
 * the provider's onEnabled/onUpdate and the settings screen arm it from the
 * other sides.
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
