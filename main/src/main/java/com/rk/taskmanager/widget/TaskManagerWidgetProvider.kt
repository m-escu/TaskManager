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
 * APPWIDGET_UPDATE at least every [android.appwidget.AppWidgetManager]
 * period (30 minutes here); each pass samples public-API stats once and
 * renders. Nothing is kept running between updates — battery cost is zero.
 *
 * The live mode is a separate foreground service ([WidgetLiveService])
 * toggled from the QS tile; this provider never starts it, because widget
 * broadcast receivers are a background context on Android 12+ and FGS
 * starts from there are rejected.
 */
class TaskManagerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (appWidgetIds.isEmpty()) return

        // goAsync: the CPU sample needs ~350ms; receiver code must not block
        // the main thread for that long.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val cpu = WidgetStats.cpuUsage()
                val (used, total) = WidgetStats.readRam(context)
                val batt = WidgetStats.readBatteryPercent(context)
                WidgetRenderer.push(
                    context = context,
                    cpuPercent = cpu,
                    ramUsed = used,
                    ramTotal = total,
                    batteryPercent = batt,
                    live = WidgetLiveService.isRunning,
                )
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
