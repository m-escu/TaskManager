package com.rk.taskmanager.widget

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile that toggles a LIVE session of the monitor service
 * ([WidgetLiveService]). The service may also be kept alive independently
 * by the "permanent notification" setting; the tile only reflects and
 * controls its own session ([WidgetLiveService.liveSession]).
 *
 * TileService.onClick runs while SystemUI holds a binding to this service,
 * which counts as a foreground context — so starting the FGS from here is
 * allowed on all supported Android versions (unlike widget receivers).
 */
class LiveTileService : TileService() {

    override fun onStartListening() {
        // Re-sync whenever the tile panel becomes visible.
        refreshTile()
    }

    override fun onClick() {
        val intent = Intent(this, WidgetLiveService::class.java)
        if (WidgetLiveService.liveSession) {
            // Tile off. The service keeps running if the permanent
            // notification setting wants it; the service decides.
            startService(intent.setAction(WidgetLiveService.ACTION_STOP_QS))
            WidgetLiveService.requestStopTileSession()
        } else {
            startForegroundService(intent.setAction(WidgetLiveService.ACTION_START))
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        tile.state = if (WidgetLiveService.liveSession) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}
