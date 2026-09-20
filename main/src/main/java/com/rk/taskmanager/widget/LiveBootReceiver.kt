package com.rk.taskmanager.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rk.commons.settings.Settings

/**
 * Restores the permanent live-monitor notification after a reboot — but only
 * when BOTH settings allow it: "Permanent notification" (the mode itself)
 * and "Start at boot" (the user's opt-out for restarts). The QS tile session
 * is intentionally NOT restored: tiles are user-momentary, the permanent
 * notification setting is the only source that survives restarts.
 *
 * FGS starts from BOOT_COMPLETED are allowed for specialUse services (the
 * type WidgetLiveService declares); if POST_NOTIFICATIONS was never granted
 * the service still runs, just without a visible notification.
 */
class LiveBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings.permanentNotification || !Settings.startAtBoot) return

        runCatching {
            context.startForegroundService(
                Intent(context, WidgetLiveService::class.java)
                    .setAction(WidgetLiveService.ACTION_START_NOTIF)
            )
        }
    }
}
