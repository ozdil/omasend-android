package org.omarchy.omasend

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)

            if (uri != null) {
                Toast.makeText(this, "🚀 omasend: Dosya Omarchy PC'ye gönderiliyor...", Toast.LENGTH_LONG).show()
            } else if (text != null) {
                Toast.makeText(this, "⚡ omasend: Pano metni PC'ye aktarılıyor...", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}
