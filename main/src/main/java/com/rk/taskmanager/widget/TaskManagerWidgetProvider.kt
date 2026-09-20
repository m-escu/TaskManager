package com.rk.taskmanager.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Ephemeral widget mode (the default): the system schedules
 * APPWIDGET_UPDATE at least every 30 minutes; each pass samples stats once
 * and renders. A user-configurable faster cadence comes from
 * [WidgetRefreshScheduler]; nothing is kept running between updates —
 * battery cost is zero.
 *
 * The live mode is a separate foreground service ([WidgetLiveService])
 * toggled from the QS tile; this provider never starts it, because widget
 * broadcast receivers are a background context on Android 12+ and FGS
 * starts from there are rejected.
 */
class TaskManagerWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        // First instance placed -> arm the user-configurable refresh alarm.
        WidgetRefreshScheduler.schedule(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // Last instance removed -> stop waking the process for nothing.
        val manager = AppWidgetManager.getInstance(context)
        val remaining = manager?.getAppWidgetIds(
            android.content.ComponentName(context, TaskManagerWidgetProvider::class.java)
        )
        if (remaining == null || remaining.isEmpty()) {
            WidgetRefreshScheduler.cancel(context)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (appWidgetIds.isEmpty()) return

        // Self-heal point: the system cadence is the one update that always
        // comes, so re-arm the user-configurable alarm chain here too (e.g.
        // after reboot before the app process ever ran).
        WidgetRefreshScheduler.schedule(context)

        // goAsync: the CPU sample needs ~350ms; receiver code must not block
        // the main thread for that long.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetRefresher.refresh(context)
            } catch (t: Throwable) {
                Log.w(TAG, "ephemeral widget update failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "TaskManagerWidget"
    }
}
