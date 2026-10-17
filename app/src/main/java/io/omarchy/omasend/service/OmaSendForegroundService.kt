package io.omarchy.omasend.service

import io.omarchy.omasend.clipboard.ClipboardTileService
import io.omarchy.omasend.ui.OmaI18n
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.omarchy.omasend.MainActivity
import io.omarchy.omasend.OmaSendApp
import io.omarchy.omasend.R
import io.omarchy.omasend.network.NetworkUtils
import io.omarchy.omasend.repository.ClipboardVault

class OmaSendForegroundService : Service() {

    // Power: this service holds no locks. The Wi-Fi lock and a partial wake lock are held only
    // during transfers (TransferPowerLocks), the multicast lock only while discovery runs
    // (DiscoveryManager), and discovery stops while the screen is off (screenReceiver).
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            screenReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val app = applicationContext as? OmaSendApp ?: return
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> app.discoveryManager.onScreenOff()
                        Intent.ACTION_SCREEN_ON -> {
                            app.discoveryManager.onScreenOn()
                            // OmaID: periodic syncs pause while the screen is off; catch up once.
                            app.account.onScreenOn()
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {}

        (applicationContext as? OmaSendApp)?.account?.let {
            it.serviceRunning = true
            it.requestSync()
        }

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
                val app = applicationContext as? OmaSendApp ?: OmaSendApp.instance
                ClipboardVault.getInstance(this).processLocalClipboard(this, app)
            }
            clipboard?.addPrimaryClipChangedListener(clipboardListener)
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val ip = NetworkUtils.getLocalIpAddress()
        val deviceName = NetworkUtils.getDeviceName(this)

        val notification = NotificationCompat.Builder(this, OmaSendApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_omasend)
            .setContentTitle("OmaSend Active")
            .setContentText("$deviceName ready on $ip:53317")
            .setContentIntent(pendingIntent)
            .addAction(0, OmaI18n.t("clip_tile_label"), ClipboardTileService.shareIntent(this))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                OmaSendApp.NOTIFICATION_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                OmaSendApp.NOTIFICATION_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(OmaSendApp.NOTIFICATION_SERVICE_ID, notification)
        }

        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        (applicationContext as? OmaSendApp)?.account?.serviceRunning = false
        try {
            clipboardListener?.let {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.removePrimaryClipChangedListener(it)
            }
            screenReceiver?.let { unregisterReceiver(it) }
            screenReceiver = null
        } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val ACTION_START = "io.omarchy.omasend.START_SERVICE"
        const val ACTION_STOP = "io.omarchy.omasend.STOP_SERVICE"

        fun startService(context: Context) {
            try {
                val intent = Intent(context, OmaSendForegroundService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, OmaSendForegroundService::class.java)
                context.stopService(intent)
            } catch (_: Exception) {}
        }
    }
}
