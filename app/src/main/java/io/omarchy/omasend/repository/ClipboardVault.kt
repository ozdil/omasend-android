package io.omarchy.omasend.repository

import android.content.Context
import io.omarchy.omasend.OmaSendApp
import io.omarchy.omasend.model.ClipboardEntry
import io.omarchy.omasend.network.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class ClipboardVault private constructor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val vaultFile: File by lazy {
        File(context.filesDir, VAULT_FILE_NAME)
    }

    val imageCacheDir: File by lazy {
        File(context.cacheDir, "clipboard_images").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    private val _entries = MutableStateFlow<List<ClipboardEntry>>(emptyList())
    val entries: StateFlow<List<ClipboardEntry>> = _entries.asStateFlow()

    private val hashRingLock = Any()
    private val recentHashRing = LinkedHashSet<String>()

    private val _lastReceivedHash = AtomicReference<String>("")
    var lastReceivedHash: String
        get() = _lastReceivedHash.get()
        set(value) {
            _lastReceivedHash.set(value)
            if (value.isNotBlank()) {
                recordHash(value)
            }
        }

    init {
        loadEntries()
        evictLruCacheIfNeeded()
    }

    /** [hash] must be a 64-char hex content hash (throws otherwise; it names a file). */
    fun getImageFile(hash: String): File {
        val h = io.omarchy.omasend.engine.ImageHash.require(hash)
        if (!imageCacheDir.exists()) imageCacheDir.mkdirs()
        return File(imageCacheDir, "$h.png")
    }

    fun getThumbnailFile(hash: String): File {
        return io.omarchy.omasend.engine.ThumbnailEngine.getThumbnailFile(context, hash)
    }

    fun hasThumbnail(hash: String): Boolean {
        return io.omarchy.omasend.engine.ThumbnailEngine.hasThumbnail(context, hash)
    }

    fun hasImage(hash: String): Boolean {
        if (!io.omarchy.omasend.engine.ImageHash.isValid(hash)) return false
        val file = getImageFile(hash)
        return file.exists() && file.length() > 0
    }

    fun evictLruCacheIfNeeded() {
        try {
            if (!imageCacheDir.exists()) return
            val files = imageCacheDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith("_thumb.webp") } ?: return
            val maxImages = 20
            val maxTotalBytes = 50L * 1024L * 1024L // 50 MB
            val sorted = files.sortedBy { it.lastModified() }
            var currentTotalBytes = sorted.sumOf { it.length() }
            var currentCount = sorted.size

            for (file in sorted) {
                if (currentCount <= maxImages && currentTotalBytes <= maxTotalBytes) {
                    break
                }
                val size = file.length()
                val baseName = file.nameWithoutExtension
                if (file.delete()) {
                    currentCount--
                    currentTotalBytes -= size
                    val thumbFile = File(imageCacheDir, "${baseName}_thumb.webp")
                    if (thumbFile.exists()) {
                        thumbFile.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun saveImageBytes(hash: String, bytes: ByteArray): File {
        val h = io.omarchy.omasend.engine.ImageHash.require(hash)
        if (!imageCacheDir.exists()) imageCacheDir.mkdirs()
        val file = File(imageCacheDir, "$h.png")
        val tmpFile = File(imageCacheDir, "$h.tmp")
        tmpFile.writeBytes(bytes)
        tmpFile.renameTo(file)
        file.setLastModified(System.currentTimeMillis())

        try {
            io.omarchy.omasend.engine.ThumbnailEngine.generateAndCacheThumbnail(context, bytes, h)
        } catch (_: Exception) {}

        evictLruCacheIfNeeded()
        return file
    }

    /** Records clipboard text received over the core transport (prevents echoing it back). */
    fun recordRemoteText(text: String, senderName: String) {
        recordHash(computeHash(text))
        addEntry(text = text, senderName = senderName, isMine = false, contentType = "text")
    }

    fun computeHash(text: String): String {
        return NetworkUtils.computeSha256(text)
    }

    fun computeImageHash(bytes: ByteArray): String {
        return NetworkUtils.computeSha256(bytes)
    }

    fun recordHash(hash: String) {
        if (hash.isBlank()) return
        synchronized(hashRingLock) {
            recentHashRing.remove(hash)
            while (recentHashRing.size >= HASH_RING_CAPACITY) {
                val it = recentHashRing.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            recentHashRing.add(hash)
        }
        _lastReceivedHash.set(hash)
    }

    /**
     * Atomically records [hash] and returns true if it was not known yet. MainActivity and the
     * foreground service both listen for clipboard changes; with a separate check-then-record the
     * same clip could be processed (and pushed to peers) twice.
     */
    fun recordIfNew(hash: String): Boolean {
        if (hash.isBlank()) return false
        synchronized(hashRingLock) {
            if (recentHashRing.contains(hash)) return false
            recordHash(hash)
            return true
        }
    }

    fun isKnownHash(hash: String): Boolean {
        if (hash.isBlank()) return false
        return synchronized(hashRingLock) {
            recentHashRing.contains(hash)
        }
    }

    fun isDuplicateOrLoop(text: String): Boolean {
        if (text.isEmpty()) return true
        val currentHash = computeHash(text)
        return isKnownHash(currentHash)
    }

    fun updateLastReceivedHash(text: String) {
        val hash = computeHash(text)
        recordHash(hash)
    }

    fun addEntry(
        text: String = "",
        senderName: String,
        isMine: Boolean,
        contentType: String = "text",
        imageHash: String? = null,
        imageSize: Long? = null,
        thumbnailBase64: String? = null,
        width: Int? = null,
        height: Int? = null
    ): ClipboardEntry? {
        if (contentType == "text" && text.isBlank()) return null
        if (contentType == "image" && imageHash.isNullOrBlank()) return null

        val entry = ClipboardEntry(
            id = UUID.randomUUID().toString(),
            text = text,
            senderName = senderName,
            timestamp = System.currentTimeMillis(),
            isMine = isMine,
            contentType = contentType,
            imageHash = imageHash,
            imageSize = imageSize,
            thumbnailBase64 = thumbnailBase64,
            width = width,
            height = height
        )

        scope.launch {
            mutex.withLock {
                val current = _entries.value.toMutableList()
                if (contentType == "image" && imageHash != null) {
                    current.removeAll { it.contentType == "image" && it.imageHash == imageHash }
                } else if (text.isNotBlank()) {
                    current.removeAll { it.contentType == "text" && it.text == text }
                }
                current.add(0, entry)
                val trimmed = current.take(MAX_VAULT_ITEMS)
                _entries.value = trimmed
                saveEntries(trimmed)
            }
        }
        return entry
    }

    fun processIncomingClipboard(text: String, senderName: String): ClipboardEntry? {
        if (text.isBlank()) return null
        if (isSensitivePayload(text)) return null
        updateLastReceivedHash(text)
        return addEntry(text = text, senderName = senderName, isMine = false, contentType = "text")
    }

    fun processIncomingPayload(
        payload: io.omarchy.omasend.model.ClipboardPayload,
        peerIp: String = "",
        peerPort: Int = NetworkUtils.PORT,
        client: io.omarchy.omasend.network.OmaSendClient? = null
    ): ClipboardEntry? {
        if (payload.content_type == "image") {
            // The hash names files and URLs: reject anything that is not a 64-char hex digest.
            val hash = io.omarchy.omasend.engine.ImageHash.normalize(payload.image_hash) ?: return null
            recordHash(hash)

            // 1. Direct write if payload contains image data (< 512 KiB). Bytes are stored under
            // the hash only if they actually hash to it (otherwise the lazy fetch below runs).
            if (!payload.image_data_base64.isNullOrBlank()) {
                try {
                    val bytes = io.omarchy.omasend.engine.ThumbnailEngine.decodeBase64(payload.image_data_base64)
                    if (bytes != null && bytes.isNotEmpty() && io.omarchy.omasend.engine.ImageHash.matches(bytes, hash)) {
                        saveImageBytes(hash, bytes)
                    }
                } catch (_: Exception) {}
            } else if (payload.image_size != null && payload.image_size <= 512 * 1024L && !payload.thumbnail_base64.isNullOrBlank()) {
                try {
                    val bytes = io.omarchy.omasend.engine.ThumbnailEngine.decodeBase64(payload.thumbnail_base64)
                    if (bytes != null && bytes.isNotEmpty() && io.omarchy.omasend.engine.ImageHash.matches(bytes, hash)) {
                        saveImageBytes(hash, bytes)
                    }
                } catch (_: Exception) {}
            }

            // Cache incoming thumbnail payload directly to disk if available
            if (!payload.thumbnail_base64.isNullOrBlank()) {
                try {
                    val thumbFile = getThumbnailFile(hash)
                    if (!thumbFile.exists() || thumbFile.length() == 0L) {
                        val thumbBytes = io.omarchy.omasend.engine.ThumbnailEngine.decodeBase64(payload.thumbnail_base64)
                        if (thumbBytes != null && thumbBytes.isNotEmpty()) {
                            val tmpThumb = File(imageCacheDir, "${hash}_thumb.webp.tmp")
                            tmpThumb.writeBytes(thumbBytes)
                            tmpThumb.renameTo(thumbFile)
                            thumbFile.setLastModified(System.currentTimeMillis())
                        }
                    }
                } catch (_: Exception) {}
            }

            // 2. Lazy on-demand download if 512 KiB - 10 MiB
            if (!hasImage(hash) && client != null && peerIp.isNotBlank() && peerPort > 0) {
                scope.launch {
                    try {
                        // fetchClipboardImage verifies the bytes against the hash.
                        val fetchResult = client.fetchClipboardImage(peerIp, peerPort, hash)
                        fetchResult.getOrNull()?.let { bytes ->
                            saveImageBytes(hash, bytes)
                        }
                    } catch (_: Exception) {}
                }
            }

            val displayText = payload.text.ifBlank {
                val sizeStr = payload.image_size?.let { NetworkUtils.formatBytes(it) } ?: ""
                if (sizeStr.isNotBlank()) "Görsel ($sizeStr)" else "Görsel"
            }

            return addEntry(
                text = displayText,
                senderName = payload.sender_name,
                isMine = false,
                contentType = "image",
                imageHash = hash,
                imageSize = payload.image_size,
                thumbnailBase64 = payload.thumbnail_base64,
                width = payload.width,
                height = payload.height
            )
        } else {
            return processIncomingClipboard(payload.text, payload.sender_name)
        }
    }

    suspend fun fetchAndCacheImage(
        client: io.omarchy.omasend.network.OmaSendClient,
        peerIp: String,
        peerPort: Int,
        hash: String
    ): File? {
        if (!io.omarchy.omasend.engine.ImageHash.isValid(hash)) return null
        if (hasImage(hash)) {
            val file = getImageFile(hash)
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        return try {
            val res = client.fetchClipboardImage(peerIp, peerPort, hash)
            val bytes = res.getOrNull() ?: return null
            saveImageBytes(hash, bytes)
        } catch (_: Exception) {
            null
        }
    }

    fun isSensitivePayload(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false

        // 1. Private keys and certificates
        if (t.contains("BEGIN PRIVATE KEY") ||
            t.contains("BEGIN RSA PRIVATE KEY") ||
            t.contains("BEGIN OPENSSH PRIVATE KEY") ||
            t.contains("BEGIN PGP PRIVATE KEY") ||
            t.contains("BEGIN CERTIFICATE")
        ) {
            return true
        }

        // 2. High entropy API tokens and cloud credentials
        if (t.startsWith("ghp_") || t.startsWith("gho_") || t.startsWith("ghu_") || t.startsWith("ghs_") ||
            t.startsWith("glpat-") || t.startsWith("sk_live_") || t.startsWith("rk_live_") ||
            t.startsWith("AIzaSy") || t.startsWith("AKIA") || t.startsWith("ASIA") ||
            t.startsWith("xoxb-") || t.startsWith("xoxp-") ||
            (t.startsWith("eyJ") && t.count { it == '.' } == 2)
        ) {
            return true
        }

        // 3. BIP-39 12/24 word mnemonic recovery seed phrases
        val words = t.split("\\s+".toRegex())
        if ((words.size == 12 || words.size == 24) && words.all { it.all { c -> c in 'a'..'z' } }) {
            return true
        }

        // 4. Typical high-entropy password/token heuristic (20-128 chars without spaces)
        if (!t.contains(' ') && !t.contains('\n') && !t.contains('/') && !t.contains('.') && t.length in 20..128) {
            val hasUpper = t.any { it.isUpperCase() }
            val hasLower = t.any { it.isLowerCase() }
            val hasDigit = t.any { it.isDigit() }
            val hasSpecial = t.any { "!@#$%^&*()-_=+[]{}|;:,<>?".contains(it) }
            if (hasUpper && hasLower && hasDigit && hasSpecial) {
                return true
            }
        }

        return false
    }

    /**
     * Shares the current clipboard text with the user's own devices. Returns how many devices it
     * went to, or -1 when there was nothing to share. [force] re-sends text that was already
     * shared (the Quick Settings tile).
     */
    fun processLocalClipboard(context: Context, app: OmaSendApp, force: Boolean = false): Int {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return -1
            val clip = clipboard.primaryClip ?: return -1
            if (clip.itemCount == 0) return -1

            // Sensitive Clipboard Shield: Android 13+ EXTRA_IS_SENSITIVE check
            val desc = clip.description
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                if (desc.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true) {
                    return -1
                }
            }

            val text = clip.getItemAt(0)?.text?.toString() ?: return -1
            if (text.isBlank()) return -1

            // Sensitive Clipboard Shield: Pattern heuristics
            if (isSensitivePayload(text)) {
                return -1
            }

            val hash = computeHash(text)
            val isNew = recordIfNew(hash)
            if (!isNew && !force) {
                return -1
            }

            if (isNew) {
                val deviceName = NetworkUtils.getDeviceName(context)
                addEntry(text = text, senderName = deviceName, isMine = true, contentType = "text")
            }
            io.omarchy.omasend.network.CoreNode.noteLocalClipboard()

            // Automatic clipboard sync goes only to the user's OWN QR-paired devices, over
            // encrypted QUIC (never downgraded). The former push to legacy code-paired OmaIDs over
            // plain HTTP is disabled: that pairing rests on the public OmaID alone, so nothing
            // authenticates whoever answers at the stored address.
            val ownKeys = io.omarchy.omasend.crypto.DeviceKey.TrustStore(context).load().filter { it.own_device }
            if (ownKeys.isNotEmpty()) {
                scope.launch {
                    for (k in ownKeys) {
                        io.omarchy.omasend.network.CoreNode.sendClipboard(k, text)
                    }
                }
            }
            return ownKeys.size
        } catch (_: Exception) {
            return -1
        }
    }

    fun clear() {
        scope.launch {
            mutex.withLock {
                _entries.value = emptyList()
                saveEntries(emptyList())
            }
        }
    }

    private fun loadEntries() {
        scope.launch {
            mutex.withLock {
                try {
                    if (vaultFile.exists()) {
                        val content = vaultFile.readText(Charsets.UTF_8)
                        if (content.isNotBlank()) {
                            val list = json.decodeFromString(ListSerializer(ClipboardEntry.serializer()), content)
                            _entries.value = list.take(MAX_VAULT_ITEMS)
                        }
                    }
                } catch (_: Exception) {
                    _entries.value = emptyList()
                }
            }
        }
    }

    private fun saveEntries(list: List<ClipboardEntry>) {
        try {
            val content = json.encodeToString(ListSerializer(ClipboardEntry.serializer()), list)
            val tempFile = File(context.filesDir, "$VAULT_FILE_NAME.tmp")
            tempFile.writeText(content, Charsets.UTF_8)
            tempFile.renameTo(vaultFile)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val VAULT_FILE_NAME = "clipboard_vault.json"
        const val MAX_VAULT_ITEMS = 20
        const val HASH_RING_CAPACITY = 64

        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: ClipboardVault? = null

        fun getInstance(context: Context): ClipboardVault {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ClipboardVault(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
