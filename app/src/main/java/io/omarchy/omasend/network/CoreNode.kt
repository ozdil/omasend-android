package io.omarchy.omasend.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import io.omarchy.omasend.OmaSendApp
import io.omarchy.omasend.crypto.DeviceKey
import io.omarchy.omasend.model.DiscoveredPeer
import io.omarchy.omasend.model.IncomingTransferPrompt
import io.omarchy.omasend.model.TransferFileInfo
import io.omarchy.omasend.model.TransferProgressState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * are BLAKE3-verified and resumable. Unpaired peers keep using the legacy HTTP path.
 */
object CoreNode {
    private const val TAG = "OmaSendCore"
    const val QUIC_PORT = 53318

    @Volatile
    var node: OmaNode? = null
        private set

    private val quicOffers = ConcurrentHashMap.newKeySet<String>()
    private val sendListeners = ConcurrentHashMap<String, (String, Long, Long) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun start(app: OmaSendApp) {
        if (node != null) return
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
            Log.i(TAG, "core node started on UDP ${node?.boundPort()}")
        } catch (t: Throwable) {
            // The legacy HTTP transport keeps working if the native core cannot start.
            Log.w(TAG, "core node unavailable: ${t.message}")
        }
    }

    fun isQuicOffer(token: String) = quicOffers.contains(token)

    private fun addrs(key: DeviceKey.TrustedKey) = listOf("${key.ip}:${if (key.quic_port > 0) key.quic_port else QUIC_PORT}")

    fun respond(token: String, accept: Boolean) {
        quicOffers.remove(token)
        node?.respond(token, accept)
    }

    /** The QR-paired key for a discovered peer, matched by the address of its last signed request. */
    fun trustedKeyFor(context: Context, peer: DiscoveredPeer): DeviceKey.TrustedKey? =
        DeviceKey.TrustStore(context).load().firstOrNull { it.ip.isNotBlank() && it.ip == peer.ip }

    fun sendClipboard(context: Context, key: DeviceKey.TrustedKey, text: String): Boolean {
        val n = node ?: return false
        return try {
            n.sendClipboard(key.pk, addrs(key), text)
            true
        } catch (e: CoreException) {
            Log.i(TAG, "quic clipboard failed, falling back: ${e.message}")
            false
        }
    }

    /**
     * Sends content URIs over QUIC to a QR-paired peer.
     * Returns false (so the caller falls back to HTTP) when the core or the peer's QUIC
     * endpoint is unavailable; returns true once the outcome has been reported via [onState].
     */
    suspend fun trySendUris(
        context: Context,
        app: OmaSendApp,
        peer: DiscoveredPeer,
        uris: List<Uri>,
        onState: (TransferProgressState) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val n = node ?: return@withContext false
        val key = trustedKeyFor(context, peer) ?: return@withContext false
        val pfds = mutableListOf<ParcelFileDescriptor>()
        try {
            val names = mutableListOf<String>()
            val paths = mutableListOf<String>()
            for (uri in uris) {
                var name = "file"
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: "file"
                }
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext false
                pfds += pfd
                names += name
                // The core reads the already-open descriptor; no copy of the file is made.
                paths += "/proc/self/fd/${pfd.fd}"
            }
            val label = if (names.size == 1) names.first() else "${names.size} dosya"
            withContext(Dispatchers.Main) { onState(TransferProgressState.Requesting(key.name, label)) }
            val id = UUID.randomUUID().toString().replace("-", "")
            var lastUi = 0L
            sendListeners[id] = { file, done, total ->
                val now = System.currentTimeMillis()
                if (now - lastUi >= 80 || done >= total) {
                    lastUi = now
                    val pct = if (total > 0) ((done * 100) / total).toInt() else 100
                    main.post { onState(TransferProgressState.Transferring(true, key.name, file, done, total, pct)) }
                }
            }
            try {
                n.sendFiles(key.pk, addrs(key), paths, names, id)
            } finally {
                sendListeners.remove(id)
            }
            withContext(Dispatchers.Main) {
                app.soundEngine.playSendSound()
                app.hapticController.onDropBounce()
                onState(TransferProgressState.Success("$label gönderildi (şifreli)"))
            }
            true
        } catch (e: CoreException) {
            val msg = e.message ?: ""
            if (msg.contains("timed out") || msg.contains("connect", ignoreCase = true) || msg.contains("not paired")) {
                Log.i(TAG, "quic send unavailable, falling back to HTTP: $msg")
                false
            } else {
                withContext(Dispatchers.Main) { onState(TransferProgressState.Error(msg.ifBlank { "Transfer başarısız" })) }
                true
            }
        } finally {
            pfds.forEach { runCatching { it.close() } }
        }
    }

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
            try {
                // Move the verified file from app storage into Downloads/OmaSend (MediaStore).
                val (saved, finalName) = f.inputStream().use {
                    StorageUtils.saveIncomingStream(app, f.name, it, f.length())
                }
                if (saved) {
                    val size = f.length()
                    main.post {
                        runCatching {
                            app.soundEngine.playReceiveSound()
                            app.hapticController.onFileReceivedImpact()
                        }
                        app.server.onFileReceived?.invoke(finalName, size)
                    }
                }
            } finally {
                f.delete()
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
