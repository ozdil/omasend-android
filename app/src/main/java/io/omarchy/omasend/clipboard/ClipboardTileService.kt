package io.omarchy.omasend.clipboard

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.omarchy.omasend.ui.OmaI18n

/** Quick Settings tile: one tap shares the clipboard with the user's own devices. */
class ClipboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = OmaI18n.t("clip_tile_label")
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(shareIntent(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(Intent(this, ClipboardShareActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object {
        fun shareIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_SHARE,
            Intent(context, ClipboardShareActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private const val REQUEST_SHARE = 7301
    }
}
