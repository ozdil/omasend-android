package io.omarchy.omasend.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import io.omarchy.omasend.MainActivity
import io.omarchy.omasend.OmaSendApp
import io.omarchy.omasend.model.DiscoveryMode

@RequiresApi(Build.VERSION_CODES.N)
class OmaSendQuickShareTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val app = application as? OmaSendApp ?: return
        val currentMode = app.discoveryManager.discoveryMode.value

        // Cycle through modes: EVERYONE -> KNOWN_PEERS -> OFF -> EVERYONE
        val nextMode = when (currentMode) {
            DiscoveryMode.EVERYONE -> DiscoveryMode.KNOWN_PEERS
            DiscoveryMode.KNOWN_PEERS -> DiscoveryMode.OFF
            DiscoveryMode.OFF -> DiscoveryMode.EVERYONE
        }

        app.discoveryManager.setMode(nextMode)
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val app = application as? OmaSendApp
        val mode = app?.discoveryManager?.discoveryMode?.value ?: DiscoveryMode.OFF

        tile.label = "OmaSend AirBridge"
        when (mode) {
            DiscoveryMode.EVERYONE -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Everyone"
                }
            }
            DiscoveryMode.KNOWN_PEERS -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Known Devices"
                }
            }
            DiscoveryMode.OFF -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Off"
                }
            }
        }
        tile.updateTile()
    }
}
