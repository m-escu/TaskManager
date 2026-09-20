package com.rk.taskmanager.widget

import android.content.Context

/**
 * One ephemeral refresh pass, shared by the appwidget provider (system
 * cadence) and the user-configurable alarm receiver. A single sample with
 * no long-lived state — if no widget is placed, WidgetRenderer.push is a
 * no-op.
 */
internal object WidgetRefresher {

    suspend fun refresh(context: Context) {
        val cpu = WidgetStats.cpuUsage()
        val (used, total) = WidgetStats.readRam(context)
        val battery = WidgetStats.readBattery(context)
        WidgetRenderer.push(
            context = context,
            cpuPercent = cpu,
            ramUsed = used,
            ramTotal = total,
            currentUA = battery.currentUA,
            tempTenthsC = battery.tempTenthsC,
            // LIVE badge only during a QS-tile session; the permanent
            // notification alone does not make the widget "live".
            live = WidgetLiveService.liveSession,
        )
    }
}
