package io.omarchy.omasend.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/**
 * The low-latency Wi-Fi lock and a partial wake lock, held only while at least one transfer is
 * running (HTTP or QUIC, sending or receiving). Idle, the app holds neither.
 *
 * Reference counted across concurrent transfers: [begin] at transfer start, [end] exactly once at
 * finish, failure or cancel (prefer [during]). As a safety net both locks are dropped after
 * [SAFETY_TIMEOUT_MS] without a [begin] or [keepAlive], so a transfer that never reports its end
 * cannot keep the radio and CPU awake indefinitely; long transfers call [keepAlive] from their
 * progress callbacks to stay covered.
 */
object TransferPowerLocks {
    const val SAFETY_TIMEOUT_MS = 10 * 60 * 1000L
    private const val KEEP_ALIVE_EVERY_MS = 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var active = 0
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var lastArmedAt = 0L

    private val safetyRelease = Runnable {
        synchronized(this) {
            active = 0
            releaseLocked()
        }
    }

    @Synchronized
    fun begin(context: Context) {
        appContext = context.applicationContext
        active++
        if (wifiLock?.isHeld != true) {
            wifiLock = NetworkUtils.acquireHighPerfWifiLock(context.applicationContext, "OmaSend:Transfer")
        }
        armLocked()
    }

    @Synchronized
    fun end() {
        if (active == 0) return
        active--
        if (active == 0) releaseLocked()
    }

    /** Extends the safety timeout while a long transfer is still making progress. Cheap to call often. */
    fun keepAlive() {
        if (SystemClock.elapsedRealtime() - lastArmedAt < KEEP_ALIVE_EVERY_MS) return
        synchronized(this) {
            if (active > 0) armLocked()
        }
    }

    inline fun <T> during(context: Context, block: () -> T): T {
        begin(context)
        try {
            return block()
        } finally {
            end()
        }
    }

    /** (Re)acquires the wake lock with a timeout and re-schedules the safety release. */
    private fun armLocked() {
        lastArmedAt = SystemClock.elapsedRealtime()
        try {
            if (wakeLock == null) {
                val pm = appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmaSend::Transfer")?.apply {
                    setReferenceCounted(false)
                }
            }
            // Not reference counted: acquiring again only re-arms the timeout.
            wakeLock?.acquire(SAFETY_TIMEOUT_MS)
        } catch (_: Exception) {
        }
        main.removeCallbacks(safetyRelease)
        main.postDelayed(safetyRelease, SAFETY_TIMEOUT_MS)
    }

    private fun releaseLocked() {
        main.removeCallbacks(safetyRelease)
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        NetworkUtils.releaseWifiLock(wifiLock)
        wifiLock = null
    }
}
