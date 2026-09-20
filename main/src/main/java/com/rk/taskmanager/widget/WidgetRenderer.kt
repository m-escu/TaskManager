package com.rk.taskmanager.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.rk.commons.strings
import com.rk.taskmanager.MainActivity
import com.rk.taskmanager.R

/**
 * Builds and pushes the widget RemoteViews. Shared by both widget modes so
 * the ephemeral (system-scheduled) and live (FGS) paths render identically.
 */
object WidgetRenderer {

    /**
     * Renders one frame and applies it to every placed instance of the
     * widget. [cpuPercent] uses -1 for "unavailable", [currentUA] -1
     * (microamps, absolute), [ramTotal] 0 for "unavailable".
     */
    fun push(
        context: Context,
        cpuPercent: Int,
        ramUsed: Long,
        ramTotal: Long,
        currentUA: Long,
        live: Boolean,
    ) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(
            ComponentName(context, TaskManagerWidgetProvider::class.java)
        )
        if (ids.isEmpty()) return
        val views = build(context, cpuPercent, ramUsed, ramTotal, currentUA, live)
        manager.updateAppWidget(ids, views)
    }

    private fun build(
        context: Context,
        cpuPercent: Int,
        ramUsed: Long,
        ramTotal: Long,
        currentUA: Long,
        live: Boolean,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.taskmanager_widget)

        val noData = context.getString(strings.widget_no_data)
        views.setTextViewText(
            R.id.widget_cpu_value,
            if (cpuPercent >= 0) "$cpuPercent%" else noData
        )
        views.setTextViewText(
            R.id.widget_ram_value,
            if (ramTotal > 0L) {
                "${WidgetStats.formatCompact(ramUsed)}/${WidgetStats.formatCompact(ramTotal)}"
            } else noData
        )
        views.setTextViewText(
            R.id.widget_current_value,
            if (currentUA >= 0L) WidgetStats.formatCurrent(currentUA) else noData
        )
        views.setViewVisibility(
            R.id.widget_live_badge,
            if (live) View.VISIBLE else View.GONE
        )

        // Whole-surface tap opens the app.
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)

        return views
    }
}
