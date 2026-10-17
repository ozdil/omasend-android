package io.omarchy.omasend.clipboard

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import io.omarchy.omasend.OmaSendApp
import io.omarchy.omasend.ui.OmaI18n

/**
 * Invisible activity behind the Quick Settings tile and the notification button. Android only lets
 * the focused app read the clipboard, so this takes focus for a moment, shares the text with the
 * user's own devices and closes.
 */
class ClipboardShareActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing to draw; the window only exists to receive focus.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val app = application as OmaSendApp
        val n = app.clipboardVault.processLocalClipboard(this, app, force = true)
        val msg = when {
            n < 0 -> OmaI18n.t("clip_share_nothing")
            n == 0 -> OmaI18n.t("clip_share_no_devices")
            else -> OmaI18n.f("clip_share_sent", n)
        }
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
