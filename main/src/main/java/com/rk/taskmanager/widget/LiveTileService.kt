package com.rk.taskmanager.widget

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile that toggles the live widget mode
 * ([WidgetLiveService]).
 *
 * TileService.onClick runs while SystemUI holds a binding to this service,
 * which counts as a foreground context — so starting the FGS from here is
 * allowed on all supported Android versions (unlike widget receivers).
 */
class LiveTileService : TileService() {

    override fun onStartListening() {
        // Re-sync whenever the tile panel becomes visible.
        refreshTile(active = WidgetLiveService.isRunning)
    }

    override fun onClick() {
        val intent = Intent(this, WidgetLiveService::class.java)
        if (WidgetLiveService.isRunning) {
            startService(intent.setAction(WidgetLiveService.ACTION_STOP))
            refreshTile(active = false)
        } else {
            startForegroundService(intent.setAction(WidgetLiveService.ACTION_START))
            refreshTile(active = true)
        }
    }

    private fun refreshTile(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
