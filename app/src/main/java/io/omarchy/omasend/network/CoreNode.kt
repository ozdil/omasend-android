package io.omarchy.omasend.network

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.omarchy.omasend.OmaSendApp
import io.omarchy.omasend.crypto.DeviceKey
import io.omarchy.omasend.model.DiscoveredPeer
import io.omarchy.omasend.model.IncomingTransferPrompt
import io.omarchy.omasend.model.TransferFileInfo
import io.omarchy.omasend.model.TransferProgressState
import io.omarchy.omasend.ui.OmaI18n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.omasend_core.CoreException
import uniffi.omasend_core.Direction
import uniffi.omasend_core.IncomingOffer
import uniffi.omasend_core.NodeCallbacks
import uniffi.omasend_core.OmaNode
import uniffi.omasend_core.Trust
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 2 transport: the shared Rust core (iroh / QUIC + TLS 1.3) for QR-paired devices.
 * Every connection is end-to-end encrypted and authenticated with the device key; transfers
 * are BLAKE3-verified and resumable.
 *
 * QR-paired devices ONLY use this transport. There is deliberately no fallback to plaintext
 * HTTP: otherwise blocking UDP would silently downgrade a paired device, and whoever holds
 * its address would receive the data. Unpaired (nearby) peers keep the legacy HTTP path.
 */
object CoreNode {
    private const val TAG = "OmaSendCore"
    const val QUIC_PORT = 53318

    @Volatile
    var node: OmaNode? = null
        private set

    @Volatile
    private var app: OmaSendApp? = null

    private val quicOffers = ConcurrentHashMap.newKeySet<String>()
    private val sendListeners = ConcurrentHashMap<String, (String, Long, Long) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    enum class State { IDLE, STARTING, RUNNING, FAILED }

    private val _state = MutableStateFlow(State.IDLE)
    /** Readiness of the QUIC node; [node] is non-null only in [State.RUNNING]. */
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    var lastStartError: String? = null
        private set

    /**
     * Starts the node on [scope] (an IO scope): the core retries a busy port for several
     * seconds and the device key needs the Keystore, neither of which may run on the main
     * thread. Callers that need the node use [awaitNode] or handle a null [node].
     */
    fun startAsync(app: OmaSendApp, scope: CoroutineScope) {
        this.app = app
        if (!_state.compareAndSet(State.IDLE, State.STARTING) && !_state.compareAndSet(State.FAILED, State.STARTING)) return
        scope.launch(Dispatchers.IO) { start(app) }
    }

    /** Blocking start; prefer [startAsync]. */
    @Synchronized
    fun start(app: OmaSendApp) {
        this.app = app
        if (node != null) {
            _state.value = State.RUNNING
            return
        }
        _state.value = State.STARTING
        try {
            val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "incoming").apply { mkdirs() }
            node = OmaNode.start(
                DeviceKey.seed(app),
                NetworkUtils.getDeviceName(app),
                dir.absolutePath,
                QUIC_PORT.toUShort(),
                false,
                Callbacks(app)
            )
            lastStartError = null
            _state.value = State.RUNNING
            Log.i(TAG, "core node started on UDP ${node?.boundPort()}")
        } catch (t: Throwable) {
            // Unpaired peers keep working over HTTP; paired devices report a clear error.
            // This includes DeviceKey.DeviceKeyUnavailableException: the stored key is kept.
            lastStartError = t.message
            _state.value = State.FAILED
            Log.w(TAG, "core node unavailable: ${t.message}")
        }
    }

    /** The running node, waiting up to [timeoutMs] while a start is in progress. */
    suspend fun awaitNode(timeoutMs: Long = 15_000L): OmaNode? {
        node?.let { return it }
        if (_state.value != State.STARTING) return node
        withTimeoutOrNull(timeoutMs) { state.first { it != State.STARTING } }
        return node
    }

    /**
     * The QUIC port to advertise while pairing. Waits (blocking, background threads only) for a
     * start in progress so the peer learns the port the core actually bound; falls back to the
     * default port if the node is not running.
     */
    fun advertisedQuicPort(waitMs: Long = 10_000L): Int {
        val n = node ?: if (_state.value == State.STARTING) runBlocking { awaitNode(waitMs) } else null
        return n?.boundPort()?.toInt() ?: QUIC_PORT
    }

    fun isQuicOffer(token: String) = quicOffers.contains(token)

    fun respond(token: String, accept: Boolean) {
        quicOffers.remove(token)
        node?.respond(token, accept)
    }

    // ------------------------------------------------------------------ key resolution

    /**
     * The QR-paired key a discovered peer belongs to: by the key it advertises in its beacon,
     * or (older peers that advertise none) by the address the key last authenticated from.
     * A peer advertising a key we never paired with is NOT matched by address (DHCP reuse).
     */
    fun keyFor(keys: List<DeviceKey.TrustedKey>, peer: DiscoveredPeer): DeviceKey.TrustedKey? =
        if (peer.pk.isNotBlank()) keys.firstOrNull { it.pk == peer.pk }
        else keys.firstOrNull { it.ip.isNotBlank() && it.ip == peer.ip }

    fun trustedKeyFor(context: Context, peer: DiscoveredPeer): DeviceKey.TrustedKey? =
        keyFor(DeviceKey.TrustStore(context).load(), peer)

    /** Candidate QUIC addresses: where discovery sees the key now, then its last known address. */
    fun addrsFor(key: DeviceKey.TrustedKey, extraIp: String? = null): List<String> {
        val port = if (key.quic_port in 1..65535) key.quic_port else QUIC_PORT
        val ips = LinkedHashSet<String>()
        app?.discoveryManager?.peers?.value?.forEach { p ->
            if (p.pk.isNotBlank() && p.pk == key.pk && p.ip.isNotBlank()) ips += p.ip
        }
        if (!extraIp.isNullOrBlank()) ips += extraIp
        if (key.ip.isNotBlank()) ips += key.ip
        return ips.map { "$it:$port" }
    }

    private fun friendlyError(name: String, raw: String): String {
        val m = raw.lowercase()
        return when {
            m.contains("declined") -> OmaI18n.t("err_declined").format(name)
            m.contains("not paired") || m.contains("refused") -> OmaI18n.t("err_not_paired_remote").format(name)
            m.contains("timed out") || m.contains("unreachable") || m.contains("connect") || m.contains("no address") ||
                m.contains("handshake") || m.contains("certificate") ->
                OmaI18n.f("err_unreachable_encrypted", name, QUIC_PORT)
            else -> raw.ifBlank { OmaI18n.t("err_transfer_failed") }
        }
    }

    // ------------------------------------------------------------------ clipboard

    /** Encrypted clipboard push to a paired key. Never downgraded to HTTP. */
    fun sendClipboard(key: DeviceKey.TrustedKey, text: String, extraIp: String? = null): Result<Unit> {
        // Blocking network call: always invoked off the main thread, so waiting for a start that
        // is still in progress (A5: the node now starts asynchronously) is fine here.
        val n = node ?: (if (_state.value == State.STARTING) runBlocking { awaitNode() } else null)
            ?: return Result.failure(IllegalStateException(OmaI18n.t("err_core_not_running")))
        val addrs = addrsFor(key, extraIp)
        if (addrs.isEmpty()) return Result.failure(IllegalStateException(friendlyError(key.name, "no address")))
        return try {
            n.sendClipboard(key.pk, addrs, text)
            Result.success(Unit)
        } catch (e: CoreException) {
            Log.i(TAG, "quic clipboard to ${key.name} failed: ${e.message}")
            Result.failure(IllegalStateException(friendlyError(key.name, e.message ?: "")))
        }
    }

    // ------------------------------------------------------------------ files

    /**
     * Sends content URIs to a discovered peer. Returns false only when the peer is NOT a
     * QR-paired device (the caller then uses the legacy HTTP path). For paired devices the
     * outcome is always reported through [onState] and true is returned.
     *
     * Every outgoing send (QUIC and the HTTP fallback) goes through here first, so this is also
     * where [UriGuard] rejects `file:` URIs and URIs of OmaSend's own providers: the batch is
     * refused, reported as an error and true is returned so no fallback runs.
     */
    suspend fun trySendUris(
        context: Context,
        app: OmaSendApp,
        peer: DiscoveredPeer,
        uris: List<Uri>,
        onState: (TransferProgressState) -> Unit
    ): Boolean {
        if (rejectUnsafeUris(context, uris, onState)) return true
        val key = trustedKeyFor(context, peer) ?: return false
        sendUris(context, app, key, uris, peer.ip, onState)
        return true
    }

    /** True (and an error reported) when any URI must not be sent; see [UriGuard]. */
    private suspend fun rejectUnsafeUris(
        context: Context,
        uris: List<Uri>,
        onState: (TransferProgressState) -> Unit
    ): Boolean {
        val rejected = UriGuard.rejected(context, uris)
        if (rejected.isEmpty()) return false
        Log.w(TAG, "refusing to send ${rejected.size} URI(s) from a file: scheme or OmaSend's own provider: $rejected")
        withContext(Dispatchers.Main) { onState(TransferProgressState.Error(UriGuard.REJECTED_MESSAGE)) }
        return true
    }

    suspend fun sendUris(
        context: Context,
        app: OmaSendApp,
        key: DeviceKey.TrustedKey,
        uris: List<Uri>,
        extraIp: String?,
        onState: (TransferProgressState) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (rejectUnsafeUris(context, uris, onState)) return@withContext
        val n = awaitNode()
        if (n == null) {
            withContext(Dispatchers.Main) { onState(TransferProgressState.Error(OmaI18n.t("err_core_not_running"))) }
            return@withContext
        }
        val addrs = addrsFor(key, extraIp)
        if (addrs.isEmpty()) {
            withContext(Dispatchers.Main) { onState(TransferProgressState.Error(friendlyError(key.name, "no address"))) }
            return@withContext
        }
        val pfds = mutableListOf<ParcelFileDescriptor>()
        try {
            val names = mutableListOf<String>()
            val paths = mutableListOf<String>()
            for (uri in uris) {
                var name = "file"
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: "file"
                }
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd == null) {
                    withContext(Dispatchers.Main) { onState(TransferProgressState.Error(OmaI18n.t("err_open_file").format(name))) }
                    return@withContext
                }
                pfds += pfd
                names += name
                // The core reads the already-open descriptor; no copy of the file is made.
                paths += "/proc/self/fd/${pfd.fd}"
            }
            val label = if (names.size == 1) names.first() else OmaI18n.t("n_files").format(names.size)
            withContext(Dispatchers.Main) {
                onState(
                    if (key.own_device) TransferProgressState.Requesting(key.name, label)
                    else TransferProgressState.WaitingConsent(key.name)
                )
            }
            val id = UUID.randomUUID().toString().replace("-", "")
            var lastUi = 0L
            var speedFile = ""
            var speed: TransferSpeedCalculator? = null
            sendListeners[id] = { file, done, total ->
                val calc = speed?.takeIf { speedFile == file }
                    ?: TransferSpeedCalculator(total).also { speed = it; speedFile = file }
                val m = calc.update(done)
                val now = System.currentTimeMillis()
                if (now - lastUi >= 80 || done >= total) {
                    lastUi = now
                    val pct = if (total > 0) ((done * 100) / total).toInt() else 100
                    main.post { onState(TransferProgressState.Transferring(true, key.name, file, done, total, pct, m.speedMBps, m.etaSeconds)) }
                }
            }
            try {
                n.sendFiles(key.pk, addrs, paths, names, id)
            } finally {
                sendListeners.remove(id)
            }
            withContext(Dispatchers.Main) {
                app.soundEngine.playSendSound()
                app.hapticController.onDropBounce()
                onState(TransferProgressState.Success(OmaI18n.f("sent_encrypted", label, key.name)))
            }
        } catch (e: CoreException) {
            val msg = friendlyError(key.name, e.message ?: "")
            withContext(Dispatchers.Main) { onState(TransferProgressState.Error(msg)) }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onState(TransferProgressState.Error(e.message ?: OmaI18n.t("err_transfer_failed"))) }
        } finally {
            pfds.forEach { runCatching { it.close() } }
        }
    }

    /** Tells the user a received file could not be moved to Downloads and where it was kept. */
    private fun notifySaveFailed(app: OmaSendApp, file: File, reason: String?) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val text = "${file.name} could not be saved to Downloads" +
                (if (reason.isNullOrBlank()) "" else " ($reason)") +
                ". It was kept in the app's storage: ${file.absolutePath}"
            val notification = NotificationCompat.Builder(app, OmaSendApp.CHANNEL_TRANSFERS)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("OmaSend: file not saved")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(app).notify(SAVE_FAILED_NOTIFICATION_BASE + (file.absolutePath.hashCode() and 0xFFFF), notification)
        }
    }

    private const val SAVE_FAILED_NOTIFICATION_BASE = 0x4F000

    private class Callbacks(private val app: OmaSendApp) : NodeCallbacks {
        private val store by lazy { DeviceKey.TrustStore(app) }

        override fun trust(peerPk: String): Trust {
            val k = store.get(peerPk) ?: return Trust.UNKNOWN
            return if (k.own_device) Trust.OWN else Trust.PAIRED
        }

        override fun peerName(peerPk: String): String = store.get(peerPk)?.name ?: ""

        override fun incomingOffer(offer: IncomingOffer) {
            quicOffers.add(offer.transferId)
            val prompt = IncomingTransferPrompt(
                token = offer.transferId,
                senderId = offer.peerPk,
                senderName = offer.peerName,
                senderIp = store.get(offer.peerPk)?.ip ?: "",
                files = offer.files.map { TransferFileInfo(it.name, it.size.toLong()) },
                totalSizeBytes = offer.totalSize.toLong()
            )
            main.post { app.server.onIncomingTransferPrompt?.invoke(prompt) }
        }

        override fun progress(transferId: String, direction: Direction, fileName: String, done: ULong, total: ULong) {
            if (direction == Direction.SEND) sendListeners[transferId]?.invoke(fileName, done.toLong(), total.toLong())
        }

        override fun fileReceived(transferId: String, peerPk: String, path: String) {
            val f = File(path)
            var saved = false
            try {
                // Move the verified file from app storage into Downloads/OmaSend (MediaStore).
                val (ok, finalNameOrError) = f.inputStream().use {
                    StorageUtils.saveIncomingStream(app, f.name, it, f.length())
                }
                saved = ok
                if (ok) {
                    val size = f.length()
                    main.post {
                        runCatching {
                            app.soundEngine.playReceiveSound()
                            app.hapticController.onFileReceivedImpact()
                        }
                        app.server.onFileReceived?.invoke(finalNameOrError, size)
                        app.notifyFileReceived(finalNameOrError)
                    }
                } else {
                    Log.w(TAG, "could not save ${f.name} to Downloads ($finalNameOrError); kept at ${f.absolutePath}")
                    notifySaveFailed(app, f, finalNameOrError)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "could not save ${f.name} to Downloads; kept at ${f.absolutePath}", t)
                notifySaveFailed(app, f, t.message)
            } finally {
                // The received, BLAKE3-verified file is the only copy: delete it only once it is
                // safely in Downloads. On failure it stays in the app's incoming directory.
                if (saved) f.delete()
            }
        }

        override fun transferFinished(transferId: String, direction: Direction, ok: Boolean, error: String?) {
            quicOffers.remove(transferId)
            if (!ok) Log.i(TAG, "transfer $transferId ($direction) failed: $error")
        }

        override fun clipboard(peerPk: String, text: String) {
            val name = store.get(peerPk)?.name ?: "OmaSend"
            app.clipboardVault.recordRemoteText(text, name)
            main.post {
                runCatching {
                    val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("OmaSend", text))
                    app.soundEngine.playReceiveSound()
                    app.hapticController.onClipboardReceived()
                }
                app.server.onClipboardReceived?.invoke(name, text)
            }
        }
    }
}
