package io.omarchy.omasend

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.omarchy.omasend.account.OmaAccount
import io.omarchy.omasend.audio.OmaSoundEngine
import io.omarchy.omasend.crypto.OmaIdentity
import io.omarchy.omasend.model.IncomingTransferPrompt
import io.omarchy.omasend.network.DiscoveryManager
import io.omarchy.omasend.network.NetworkConnectivityWatcher
import io.omarchy.omasend.network.NetworkTransportMode
import io.omarchy.omasend.network.OmaSendClient
import io.omarchy.omasend.network.OmaSendServer
import io.omarchy.omasend.network.WanDiscoveryEngine
import io.omarchy.omasend.repository.ClipboardVault
import io.omarchy.omasend.ui.OmaI18n
import io.omarchy.omasend.ui.haptics.OmaHapticController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class OmaSendApp : Application() {
    /** Process-wide background scope for start-up work that must not block the main thread. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var discoveryManager: DiscoveryManager
        private set
    lateinit var server: OmaSendServer
        private set
    lateinit var client: OmaSendClient
        private set
    lateinit var soundEngine: OmaSoundEngine
        private set
    lateinit var hapticController: OmaHapticController
        private set
    lateinit var clipboardVault: ClipboardVault
        private set
    lateinit var omaIdentity: OmaIdentity
        private set
    lateinit var wanDiscoveryEngine: WanDiscoveryEngine
        private set
    lateinit var networkWatcher: NetworkConnectivityWatcher
        private set
    /** OmaID account (one number for all of the user's devices). */
    lateinit var account: OmaAccount
        private set

    /** Incoming transfer requests waiting for a decision, oldest first. */
    private val _incomingPrompts = MutableStateFlow<List<IncomingTransferPrompt>>(emptyList())
    val incomingPrompts: StateFlow<List<IncomingTransferPrompt>> = _incomingPrompts.asStateFlow()

    @Volatile private var resumedActivities = 0

    /** An activity of the app is resumed (on screen). */
    val isInForeground: Boolean get() = resumedActivities > 0

    /** Removes a prompt once it was answered (or its request is gone) and clears its notification. */
    fun dismissPrompt(token: String) {
        _incomingPrompts.update { list -> list.filterNot { it.token == token } }
        NotificationManagerCompat.from(this).cancel(token.hashCode())
    }

    private fun onIncomingPrompt(prompt: IncomingTransferPrompt) {
        _incomingPrompts.update { list -> list.filterNot { it.token == prompt.token } + prompt }
        if (resumedActivities > 0) return
        // App in the background: the request would expire unseen, so surface it.
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_omasend)
            .setContentTitle(OmaI18n.t("incoming_title"))
            .setContentText(OmaI18n.f("incoming_request", prompt.senderName))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(prompt.token.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notification permission not granted; the prompt still waits in the app.
        }
    }

    /**
     * Lasting proof that a file arrived: the sound is skipped in silent mode and the
     * in-app toast only shows while the app is open. Silent because the sound already played.
     */
    fun notifyFileReceived(fileName: String) {
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_omasend)
            .setContentTitle(OmaI18n.t("file_received_title"))
            .setContentText(OmaI18n.f("file_received_text", fileName))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setSilent(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_RECEIVED_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission not granted.
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannels()
        io.omarchy.omasend.network.NetworkUtils.init(this)

        soundEngine = OmaSoundEngine(this)
        hapticController = OmaHapticController(this)
        clipboardVault = ClipboardVault.getInstance(this)
        omaIdentity = OmaIdentity.getOrGenerate(this)
        wanDiscoveryEngine = WanDiscoveryEngine(this, identityProvider = { omaIdentity })

        discoveryManager = DiscoveryManager(this)
        server = OmaSendServer(this).apply {
            getDiscoveryMode = { discoveryManager.discoveryMode.value }
            onIncomingTransferPrompt = { onIncomingPrompt(it) }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumedActivities++ }
            override fun onActivityPaused(activity: Activity) { resumedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        client = OmaSendClient(this)

        networkWatcher = NetworkConnectivityWatcher(this).apply {
            onLanAvailable = {
                discoveryManager.forceRefresh()
                wanDiscoveryEngine.setNetworkMode(NetworkTransportMode.LAN)
            }
            onCellularAvailable = {
                wanDiscoveryEngine.setNetworkMode(NetworkTransportMode.WAN)
                discoveryManager.forceRefresh()
            }
            onNetworkChanged = { state ->
                if (state.isLanAvailable) {
                    discoveryManager.forceRefresh()
                }
            }
            start()
        }

        // Before the core node: it starts with relays (wan) only when an account exists.
        account = OmaAccount(this).also { it.start() }

        server.start()
        // Phase 2: encrypted QUIC transport for QR-paired devices. Started off the main thread:
        // the device key needs the Android Keystore and the core retries a busy UDP port for
        // several seconds (ANR risk). Users of CoreNode.node handle "not started yet".
        io.omarchy.omasend.network.CoreNode.startAsync(this, appScope)
        discoveryManager.start()
        // WAN rendezvous (keyed by the public OmaID) is disabled; see WanDiscoveryEngine.
        // Catch up linked devices that missed a link or removal while this app was off.
        android.os.Handler(mainLooper).postDelayed({ client.pushRoster() }, 10_000L)
    }

    fun refreshOmaIdentity(): OmaIdentity {
        omaIdentity = OmaIdentity.getOrGenerate(this)
        discoveryManager.refreshSameOwnerFlags()
        discoveryManager.forceRefresh()
        return omaIdentity
    }

    fun setCustomOmaIdentity(newOmaId: String): OmaIdentity {
        omaIdentity = OmaIdentity.save(this, newOmaId)
        discoveryManager.refreshSameOwnerFlags()
        discoveryManager.forceRefresh()
        return omaIdentity
    }

    fun resetOmaIdentity(): OmaIdentity {
        omaIdentity = OmaIdentity.reset(this)
        discoveryManager.refreshSameOwnerFlags()
        discoveryManager.forceRefresh()
        return omaIdentity
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "OmaSend Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps OmaSend reachable for nearby P2P file and clipboard sharing"
            }

            val transferChannel = NotificationChannel(
                CHANNEL_TRANSFERS,
                "OmaSend Transfers",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming and completed file transfers"
                enableVibration(true)
            }

            val accountChannel = NotificationChannel(
                CHANNEL_ACCOUNT,
                OmaI18n.t("acct_channel"),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = OmaI18n.t("acct_channel_body")
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(transferChannel)
            manager.createNotificationChannel(accountChannel)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        try {
            networkWatcher.stop()
            soundEngine.release()
        } catch (_: Exception) {}
    }

    companion object {
        const val CHANNEL_SERVICE = "omasend_service_channel"
        const val CHANNEL_TRANSFERS = "omasend_transfers_channel"
        const val CHANNEL_ACCOUNT = "omasend_account_channel"
        const val NOTIFICATION_SERVICE_ID = 1001
        const val NOTIFICATION_RECEIVED_ID = 1002

        lateinit var instance: OmaSendApp
            private set
    }
}
