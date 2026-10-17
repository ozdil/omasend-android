package io.omarchy.omasend

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import io.omarchy.omasend.account.AccountLogic
import io.omarchy.omasend.account.OmaAccount
import io.omarchy.omasend.network.NetworkUtils
import io.omarchy.omasend.service.OmaSendForegroundService
import io.omarchy.omasend.ui.OmaI18n
import io.omarchy.omasend.ui.OmaSendRoot
import io.omarchy.omasend.ui.theme.OmaSendTheme

class MainActivity : ComponentActivity() {

    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val app = application as? OmaSendApp
        app?.discoveryManager?.forceRefresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as OmaSendApp

        // Request modern notifications and camera/storage permissions
        checkAndRequestPermissions()

        // Deep link (https://omaid.app/j#…) or account notification; not again after rotation.
        if (savedInstanceState == null) handleIntent(intent)

        // Start background service
        OmaSendForegroundService.startService(this)

        // Setup local clipboard tracking
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            app.clipboardVault.processLocalClipboard(this, app)
        }
        clipboard?.addPrimaryClipChangedListener(clipboardListener)

        // Incoming transfer prompts are queued in OmaSendApp, which also notifies while backgrounded.
        app.server.onFileReceived = { filename, size ->
            Toast.makeText(
                this,
                OmaI18n.f("file_received", filename, NetworkUtils.formatBytes(size)),
                Toast.LENGTH_LONG
            ).show()
        }

        // Received clipboard text is applied silently (CoreNode); no toast per copy.
        app.server.onClipboardReceived = null

        setContent {
            OmaSendTheme {
                val prompts by app.incomingPrompts.collectAsState()
                OmaSendRoot(
                    app = app,
                    incomingPrompt = prompts.firstOrNull(),
                    onAcceptPrompt = { token ->
                        app.soundEngine.playReceiveSound()
                        app.server.acceptTransfer(token)
                        app.dismissPrompt(token)
                        Toast.makeText(this, OmaI18n.t("transfer_accepted"), Toast.LENGTH_SHORT).show()
                    },
                    onDeclinePrompt = { token ->
                        app.server.rejectTransfer(token)
                        app.dismissPrompt(token)
                        Toast.makeText(this, OmaI18n.t("transfer_declined"), Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * OmaID invite links open the account screen, which asks before joining (a link from a web
     * page must not silently add this phone to someone else's account). The fragment (#n=…&k=…)
     * carries the number and invite key and is kept: dataString includes it.
     */
    private fun handleIntent(intent: Intent?) {
        val app = application as? OmaSendApp ?: return
        if (intent == null) return
        if (intent.getBooleanExtra(OmaAccount.EXTRA_OPEN_ACCOUNT, false)) {
            app.account.requestOpen()
        }
        if (intent.action == Intent.ACTION_VIEW) {
            val link = intent.dataString
            if (link != null && AccountLogic.isInviteLink(link)) app.account.offerInvite(link)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android lets an app read the clipboard only while it has focus: share whatever was
        // copied elsewhere as soon as OmaSend comes to the front.
        if (hasFocus) (application as? OmaSendApp)?.let { it.clipboardVault.processLocalClipboard(this, it) }
    }

    override fun onResume() {
        super.onResume()
        val app = application as? OmaSendApp
        app?.discoveryManager?.forceRefresh()
        app?.networkWatcher?.forceRefresh()
        app?.account?.onForeground()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardListener?.let {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.removePrimaryClipChangedListener(it)
        }
        if (isFinishing && !isChangingConfigurations) {
            val app = application as? OmaSendApp
            if (app?.discoveryManager?.discoveryMode?.value == io.omarchy.omasend.model.DiscoveryMode.OFF) {
                app.discoveryManager.stop()
                OmaSendForegroundService.stopService(this)
            }
        }
    }
}
