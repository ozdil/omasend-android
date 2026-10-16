package io.omarchy.omasend.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.omarchy.omasend.crypto.DeviceKey
import io.omarchy.omasend.model.ClipboardPayload
import io.omarchy.omasend.model.IncomingTransferPrompt
import io.omarchy.omasend.model.TransferRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class OmaSendServer(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    // Token -> PendingTransfer
    data class PendingState(
        val prompt: IncomingTransferPrompt,
        val expiresAt: Long,
        var status: String, // "PENDING", "ACCEPTED", "REJECTED"
        val remainingFiles: AtomicInteger = AtomicInteger(prompt.files.size.coerceAtLeast(1)),
        /** v2: key of the verified sender; uploads must be signed by the same key. */
        val senderPk: String? = null
    )

    private val pendingTransfers = ConcurrentHashMap<String, PendingState>()

    // Protocol v2 (Phase 1): QR-paired Ed25519 device keys.
    private val trustStore by lazy { DeviceKey.TrustStore(context) }
    var onDevicePaired: ((String) -> Unit)? = null

    private fun v2Verify(headers: Map<String, String>, method: String, pathAndQuery: String, bodyTag: String, peerIp: String) =
        DeviceKey.verifyRequest(trustStore, headers, method, pathAndQuery, bodyTag, peerIp)

    private sealed class BodyResult {
        class Ok(val bytes: ByteArray) : BodyResult()
        /** Missing, empty or larger than allowed. */
        object Invalid : BodyResult()
        object TimedOut : BodyResult()
        /** The peer closed the connection before Content-Length bytes arrived. */
        object Incomplete : BodyResult()
    }

    /** Reads exactly [contentLength] (1..[max]) bytes before the monotonic [deadlineMs]. */
    private fun readBody(input: InputStream, contentLength: Long, max: Long, deadlineMs: Long): BodyResult {
        if (contentLength <= 0 || contentLength > max) return BodyResult.Invalid
        val bytes = ByteArray(contentLength.toInt())
        var total = 0
        while (total < bytes.size) {
            if (System.currentTimeMillis() > deadlineMs) return BodyResult.TimedOut
            val r = try {
                input.read(bytes, total, bytes.size - total)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            }
            if (r == -1) return BodyResult.Incomplete
            total += r
        }
        return BodyResult.Ok(bytes)
    }

    /** [readBody] for control endpoints: on failure sends the error response and returns null. */
    private fun readBodyOrRespond(
        input: InputStream,
        output: OutputStream,
        contentLength: Long,
        max: Long,
        deadlineMs: Long,
        timeoutMessage: String = "Request body timed out"
    ): ByteArray? = when (val r = readBody(input, contentLength, max, deadlineMs)) {
        is BodyResult.Ok -> r.bytes
        BodyResult.Invalid -> {
            sendResponse(output, 400, "Bad Request", "application/json", "{\"error\":\"Invalid or excessive payload\"}")
            null
        }
        BodyResult.TimedOut -> {
            sendResponse(output, 408, "Request Timeout", "application/json", "{\"error\":\"$timeoutMessage\"}")
            null
        }
        BodyResult.Incomplete -> {
            sendResponse(output, 400, "Bad Request", "application/json", "{\"error\":\"Incomplete payload\"}")
            null
        }
    }

    var onIncomingTransferPrompt: ((IncomingTransferPrompt) -> Unit)? = null
    var onClipboardReceived: ((senderName: String, text: String) -> Unit)? = null
    var onFileReceived: ((filename: String, sizeBytes: Long) -> Unit)? = null

    companion object {
        const val MAX_GLOBAL_CONNECTIONS = 8
        const val MAX_PER_PEER_CONNECTIONS = 2
        const val MAX_HEADER_SIZE = 65536 // 64 KiB ceiling to prevent header DoS
        const val MAX_CONTROL_BODY = 65536 // 64 KiB ceiling for control JSON payloads
        const val MAX_CLIPBOARD_BODY = 1048576 // 1 MiB ceiling matching Omarchy standard
        const val HEADER_READ_TIMEOUT_MS = 15000L // 15s monotonic deadline for header read
        const val CONTROL_DEADLINE_MS = 30000L // 30s monotonic end-to-end deadline for control requests
        const val MAX_FAILED_AUTH_ATTEMPTS = 5
        const val FAILED_AUTH_WINDOW_MS = 60000L // 1 minute window
        const val AUTH_BLOCK_DURATION_MS = 60000L // 1 minute block
    }

    private data class FailedAttemptRecord(
        var count: Int,
        var firstAttemptTime: Long,
        var blockedUntil: Long
    )

    private val activeGlobalConnections = AtomicInteger(0)
    private val activePerPeerConnections = ConcurrentHashMap<String, AtomicInteger>()
    private val failedAuthAttempts = ConcurrentHashMap<String, FailedAttemptRecord>()
    private val trustedPeers = ConcurrentHashMap<String, Long>()

    fun addTrustedPeer(senderId: String) {
        if (senderId.isNotBlank()) {
            trustedPeers[senderId] = System.currentTimeMillis()
        }
    }

    fun isPeerTrusted(senderId: String): Boolean {
        if (senderId.isBlank()) return false
        return trustedPeers.containsKey(senderId)
    }

    fun acceptTransfer(token: String) {
        if (CoreNode.isQuicOffer(token)) {
            CoreNode.respond(token, true)
            return
        }
        val state = pendingTransfers[token] ?: return
        state.status = "ACCEPTED"
        addTrustedPeer(state.prompt.senderId)
    }

    fun rejectTransfer(token: String) {
        if (CoreNode.isQuicOffer(token)) {
            CoreNode.respond(token, false)
            return
        }
        pendingTransfers[token]?.status = "REJECTED"
    }

    fun start() {
        if (serverJob != null && serverJob?.isActive == true) return
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    receiveBufferSize = NetworkUtils.SOCKET_BUFFER_SIZE
                    bind(java.net.InetSocketAddress(NetworkUtils.PORT))
                }

                while (isActive) {
                    val clientSocket = try {
                        serverSocket?.accept() ?: break
                    } catch (_: Exception) {
                        break
                    }
                    NetworkUtils.configureHighThroughputSocket(clientSocket)

                    val remoteAddr = clientSocket.inetAddress
                    if (remoteAddr == null) {
                        try { clientSocket.close() } catch (_: Exception) {}
                        continue
                    }
                    val peerIp = remoteAddr.hostAddress ?: ""

                    // Strict Global Concurrency Limit BEFORE launching work
                    if (activeGlobalConnections.get() >= MAX_GLOBAL_CONNECTIONS) {
                        try { clientSocket.close() } catch (_: Exception) {}
                        continue
                    }

                    // Strict Per-Peer Concurrency Limit BEFORE launching work
                    val peerCounter = activePerPeerConnections.computeIfAbsent(peerIp) { AtomicInteger(0) }
                    if (peerCounter.get() >= MAX_PER_PEER_CONNECTIONS) {
                        try { clientSocket.close() } catch (_: Exception) {}
                        continue
                    }

                    // Acquire concurrency slots
                    activeGlobalConnections.incrementAndGet()
                    peerCounter.incrementAndGet()

                    scope.launch {
                        try {
                            handleClient(clientSocket, peerIp)
                        } finally {
                            activeGlobalConnections.decrementAndGet()
                            if (peerCounter.decrementAndGet() <= 0) {
                                activePerPeerConnections.remove(peerIp, peerCounter)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    var getDiscoveryMode: (() -> io.omarchy.omasend.model.DiscoveryMode)? = null

    // V2-SEC-01 interim fix: OmaID is broadcast in beacons and headers, so a claimed OmaID
    // (including our own) proves nothing. Only explicitly paired OmaIDs count here.
    private fun isPairedOrKnownOmaId(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val app = context.applicationContext as? io.omarchy.omasend.OmaSendApp
        return app?.discoveryManager?.isOmaIdPaired(candidate) == true
    }

    // Paired OmaID AND the request comes from the IP stored at pairing time.
    private fun isPairedOmaIdAtIp(candidate: String?, peerIp: String): Boolean {
        if (candidate.isNullOrBlank() || peerIp.isBlank()) return false
        val app = context.applicationContext as? io.omarchy.omasend.OmaSendApp
        val savedIp = app?.discoveryManager?.getPairedIp(candidate) ?: return false
        return savedIp == peerIp
    }

    private fun handleClient(socket: Socket, peerIp: String) {
        try {
            val monotonicStart = System.currentTimeMillis()
            val headerDeadline = monotonicStart + HEADER_READ_TIMEOUT_MS

            socket.soTimeout = 3000 // 3-second SO_TIMEOUT to periodically verify monotonic deadline
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            // Read HTTP headers with strict byte ceiling and monotonic deadline
            val headerBytes = ByteArrayOutputStream()
            var prev1 = -1
            var prev2 = -1
            var prev3 = -1
            var curr: Int

            while (true) {
                if (System.currentTimeMillis() > headerDeadline) {
                    socket.close()
                    return
                }
                try {
                    curr = input.read()
                } catch (_: java.net.SocketTimeoutException) {
                    if (System.currentTimeMillis() > headerDeadline) {
                        socket.close()
                        return
                    }
                    continue
                }
                if (curr == -1) {
                    socket.close()
                    return
                }
                headerBytes.write(curr)
                if (headerBytes.size() > MAX_HEADER_SIZE) {
                    socket.close()
                    return
                }
                if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && curr == '\n'.code) {
                    break
                }
                prev3 = prev2
                prev2 = prev1
                prev1 = curr
            }

            val headerStr = headerBytes.toString("UTF-8")
            val lines = headerStr.split("\r\n")
            if (lines.isEmpty()) {
                socket.close()
                return
            }

            val requestLine = lines[0].split(" ")
            if (requestLine.size < 2) {
                socket.close()
                return
            }

            val method = requestLine[0]
            val fullPath = requestLine[1]
            val path = if (fullPath.contains("?")) fullPath.substringBefore("?") else fullPath
            val query = if (fullPath.contains("?")) fullPath.substringAfter("?") else ""

            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                val colon = line.indexOf(':')
                if (colon != -1) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L

            // Check network scope: allow private/local IPs or WAN connections carrying valid OmaID / active token
            val isPrivatePeer = NetworkUtils.isPrivateOrLocalIp(peerIp)
            val headerOmaId = headers["x-omasend-omaid"] ?: getQueryParam(query, "oma_id").ifBlank { null }
            val queryToken = getQueryParam(query, "token").ifBlank { null }
            val hasValidActiveToken = queryToken != null && pendingTransfers.containsKey(queryToken)

            if (!isPrivatePeer && !isPairedOrKnownOmaId(headerOmaId) && !hasValidActiveToken &&
                method != "POST" // POST /api/p2p/request and /api/p2p/clipboard may carry oma_id inside JSON body
            ) {
                sendResponse(output, 403, "Forbidden", "application/json", "{\"error\":\"Unauthorized WAN origin\"}")
                return
            }

            // Early Visibility Check: reject connections if visibility is turned OFF.
            // V2-SEC-01: a claimed same-owner OmaID no longer bypasses this.
            val currentMode = getDiscoveryMode?.invoke()
            if (currentMode == io.omarchy.omasend.model.DiscoveryMode.OFF &&
                path != "/api/status" && path != "/api/v2/pair"
            ) {
                sendResponse(output, 403, "Forbidden", "application/json", "{\"error\":\"Visibility is Off\"}")
                return
            }

            val remainingDeadline = monotonicStart + CONTROL_DEADLINE_MS

            when {
                path == "/api/status" || path == "/api/p2p/ping" -> {
                    handlePing(output)
                }
                method == "POST" && path == "/api/v2/pair" -> {
                    handlePairV2(input, output, contentLength, peerIp, remainingDeadline)
                }
                method == "POST" && path == "/api/p2p/request" -> {
                    handleTransferRequest(input, output, headers, contentLength, peerIp, remainingDeadline, fullPath)
                }
                method == "GET" && path == "/api/p2p/decision" -> {
                    handleDecisionQuery(output, query)
                }
                method == "POST" && path == "/api/p2p/upload" -> {
                    handleFileUpload(socket, input, output, query, contentLength, headers, peerIp, fullPath)
                }
                method == "POST" && path == "/api/p2p/clipboard" -> {
                    handleClipboard(input, output, headers, query, contentLength, peerIp, remainingDeadline, fullPath)
                }
                method == "GET" && path == "/api/p2p/clipboard/image" -> {
                    handleClipboardImageDownload(output, headers, query, peerIp)
                }
                else -> {
                    sendResponse(output, 404, "Not Found", "application/json", "{\"error\":\"Not found\"}")
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handlePing(output: OutputStream) {
        // V2-INFO-01: unauthenticated status must not reveal OmaID, device id or device name.
        val resp = """
            {
                "status": "OK",
                "mode": "ALL"
            }
        """.trimIndent()
        sendResponse(output, 200, "OK", "application/json", resp)
    }

    private fun handleTransferRequest(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
        contentLength: Long,
        peerIp: String,
        deadlineMs: Long,
        pathAndQuery: String = "/api/p2p/request"
    ) {
        val bodyBytes = readBodyOrRespond(input, output, contentLength, MAX_CONTROL_BODY.toLong(), deadlineMs) ?: return

        try {
            val bodyJson = String(bodyBytes, Charsets.UTF_8)
            val req = json.decodeFromString<TransferRequest>(bodyJson)
            val token = UUID.randomUUID().toString().replace("-", "")

            val headerOmaId = headers["x-omasend-omaid"]
            val senderOmaId = when {
                !headerOmaId.isNullOrBlank() -> headerOmaId
                req.oma_id.isNotBlank() -> req.oma_id
                io.omarchy.omasend.crypto.OmaIdentity.isValid(req.sender_id) -> req.sender_id
                else -> ""
            }

            val isPrivatePeer = NetworkUtils.isPrivateOrLocalIp(peerIp)

            val v2 = v2Verify(headers, "POST", pathAndQuery, DeviceKey.bodyTagJson(bodyBytes), peerIp)
            if (v2 is DeviceKey.AuthResult.Rejected) {
                sendResponse(output, 401, "Unauthorized", "application/json", "{\"error\":\"Signature rejected: ${v2.reason}\"}")
                return
            }
            val verifiedKey = (v2 as? DeviceKey.AuthResult.Trusted)?.key

            // Reject unauthenticated WAN transfer requests unless verified device key or paired OmaID
            if (!isPrivatePeer && verifiedKey == null && !isPairedOrKnownOmaId(senderOmaId)) {
                sendResponse(output, 403, "Forbidden", "application/json", "{\"error\":\"WAN transfer requires verified OmaID\"}")
                return
            }

            if (!StorageUtils.hasEnoughStorageSpace(context, req.total_size_bytes)) {
                sendResponse(output, 507, "Insufficient Storage", "application/json", "{\"error\":\"Insufficient storage space on device\"}")
                return
            }

            val prompt = IncomingTransferPrompt(
                token = token,
                senderId = req.sender_id,
                senderName = verifiedKey?.name ?: req.sender_name,
                senderIp = req.sender_ip.ifBlank { peerIp },
                files = req.files,
                totalSizeBytes = req.total_size_bytes
            )

            // Phase 1: only a cryptographically verified key that was QR-paired as "own device"
            // may auto-accept. A claimed OmaID never does.
            if (verifiedKey != null && verifiedKey.own_device) {
                pendingTransfers[token] = PendingState(
                    prompt = prompt,
                    expiresAt = System.currentTimeMillis() + 600000L,
                    status = "ACCEPTED",
                    senderPk = verifiedKey.pk
                )
                sendResponse(output, 200, "OK", "application/json", """{"status":"ACCEPTED","token":"$token","verified_device":true}""")
                return
            }

            pendingTransfers[token] = PendingState(
                prompt = prompt,
                expiresAt = System.currentTimeMillis() + 30000L,
                status = "PENDING",
                senderPk = verifiedKey?.pk
            )

            Handler(Looper.getMainLooper()).post {
                onIncomingTransferPrompt?.invoke(prompt)
            }

            val resp = """{"status":"PENDING","token":"$token"}"""
            sendResponse(output, 200, "OK", "application/json", resp)
        } catch (_: Exception) {
            sendResponse(output, 400, "Bad Request", "application/json", "{\"error\":\"Invalid request\"}")
        }
    }

    private fun handleDecisionQuery(output: OutputStream, query: String) {
        val token = getQueryParam(query, "token")
        val state = pendingTransfers[token]
        if (state == null) {
            sendResponse(output, 404, "Not Found", "application/json", "{\"status\":\"NOT_FOUND\"}")
            return
        }

        if (System.currentTimeMillis() > state.expiresAt) {
            pendingTransfers.remove(token)
            sendResponse(output, 200, "OK", "application/json", "{\"status\":\"EXPIRED\"}")
            return
        }

        val resp = """{"status":"${state.status}"}"""
        sendResponse(output, 200, "OK", "application/json", resp)
    }

    private fun handleFileUpload(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        query: String,
        contentLength: Long,
        headers: Map<String, String> = emptyMap(),
        peerIp: String = "",
        pathAndQuery: String = ""
    ) {
        val token = getQueryParam(query, "token")
        val filenameRaw = getQueryParam(query, "filename")
        val filename = try {
            URLDecoder.decode(filenameRaw, "UTF-8")
        } catch (_: Exception) {
            filenameRaw
        }

        if (contentLength < 0 || contentLength > StorageUtils.MAX_FILE_SIZE) {
            sendResponse(output, 400, "Bad Request", "application/json", "{\"error\":\"Invalid or excessive file size\"}")
            return
        }

        val state = pendingTransfers[token]
        if (state == null || state.status != "ACCEPTED" || System.currentTimeMillis() > state.expiresAt) {
            sendResponse(output, 403, "Forbidden", "application/json", "{\"error\":\"Unauthorized or expired transfer\"}")
            return
        }

        if (state.senderPk != null) {
            val v2 = v2Verify(headers, "POST", pathAndQuery, DeviceKey.bodyTagStream(contentLength), peerIp)
            if (v2 !is DeviceKey.AuthResult.Trusted || v2.key.pk != state.senderPk) {
                sendResponse(output, 401, "Unauthorized", "application/json", "{\"error\":\"Upload must be signed by the device that requested the transfer\"}")
                return
            }
        }

        // Extend token expiration while active upload is in progress for multi-file batches
        val uploadDurationMs = 60000L + ((contentLength / (256 * 1024L)) * 1000L).coerceAtLeast(30000L)
        val uploadDeadline = System.currentTimeMillis() + uploadDurationMs
        pendingTransfers[token] = state.copy(expiresAt = uploadDeadline + 30000L)

        // Configure socket timeout for active continuous streaming (30 seconds per I/O chunk)
        try {
            socket.soTimeout = 30000
        } catch (_: Exception) {}

        val (saved, finalName) = NetworkUtils.withHighPerfWifiLock(context, "OmaSend:ServerUpload") {
            StorageUtils.saveIncomingStream(
                context = context,
                filename = filename,
                inputStream = input,
                totalBytes = contentLength,
                deadlineMs = uploadDeadline
            )
        }

        if (saved) {
            if (state.remainingFiles.decrementAndGet() <= 0) {
                pendingTransfers.remove(token)
            }
            val app = context.applicationContext as? io.omarchy.omasend.OmaSendApp
            Handler(Looper.getMainLooper()).post {
                try {
                    app?.soundEngine?.playReceiveSound()
                    app?.hapticController?.onFileReceivedImpact()
                } catch (_: Exception) {}
                onFileReceived?.invoke(finalName, contentLength)
            }
            val resp = """{"status":"OK","filename":"$finalName","size":$contentLength}"""
            sendResponse(output, 200, "OK", "application/json", resp)
        } else {
            sendResponse(output, 500, "Internal Server Error", "application/json", "{\"error\":\"Failed to save file: $finalName\"}")
        }
    }

    private fun handlePairV2(input: InputStream, output: OutputStream, contentLength: Long, peerIp: String, deadlineMs: Long) {
        val body = readBodyOrRespond(input, output, contentLength, MAX_CONTROL_BODY.toLong(), deadlineMs) ?: return
        val obj = try {
            json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            sendResponse(output, 400, "Bad Request", "application/json", "{\"error\":\"Invalid JSON\"}")
            return
        }
        val deviceIdentity = DeviceKey.identityOrNull(context)
        if (deviceIdentity == null) {
            sendResponse(output, 503, "Service Unavailable", "application/json", "{\"error\":\"Device key unavailable on this device\"}")
            return
        }
        DeviceKey.acceptPairRequest(deviceIdentity, NetworkUtils.getDeviceName(context), obj, peerIp, CoreNode.advertisedQuicPort())
            .onSuccess { (resp, key) ->
                trustStore.upsert(key)
                Handler(Looper.getMainLooper()).post { onDevicePaired?.invoke("${key.name} (${key.device_id})") }
                sendResponse(output, 200, "OK", "application/json", resp.toString())
            }
            .onFailure {
                sendResponse(output, 403, "Forbidden", "application/json", "{\"error\":\"${(it.message ?: "Pairing failed").replace("\"", "'")}\"}")
            }
    }

    private fun isAuthRateLimited(peerIp: String): Boolean {
        val now = System.currentTimeMillis()
        val rec = failedAuthAttempts[peerIp] ?: return false
        if (now < rec.blockedUntil) return true
        if (now - rec.firstAttemptTime > FAILED_AUTH_WINDOW_MS) {
            failedAuthAttempts.remove(peerIp)
            return false
        }
        return false
    }

    private fun recordAuthAttempt(peerIp: String, success: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (success) {
            failedAuthAttempts.remove(peerIp)
            return true
        }
        val rec = failedAuthAttempts.compute(peerIp) { _, existing ->
            if (existing == null || (now - existing.firstAttemptTime > FAILED_AUTH_WINDOW_MS && now >= existing.blockedUntil)) {
                FailedAttemptRecord(count = 1, firstAttemptTime = now, blockedUntil = 0L)
            } else {
                existing.count++
                if (existing.count >= MAX_FAILED_AUTH_ATTEMPTS) {
                    existing.blockedUntil = now + AUTH_BLOCK_DURATION_MS
                }
                existing
            }
        }
        return (rec?.blockedUntil ?: 0L) <= now
    }

    private fun handleClipboard(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
        query: String,
        contentLength: Long,
        peerIp: String,
        deadlineMs: Long,
        pathAndQuery: String = "/api/p2p/clipboard"
    ) {
        // 1. Rate-limiting check: reject blocked IP immediately
        if (isAuthRateLimited(peerIp)) {
            sendResponse(
                output,
                429,
                "Too Many Requests",
                "application/json",
                "{\"error\":\"Too many failed authentication attempts. Please wait 60 seconds.\"}"
            )
            return
        }

        // 2. Monotonic body reading
        val bodyBytes = readBodyOrRespond(
            input, output, contentLength, MAX_CLIPBOARD_BODY.toLong(), deadlineMs, "Clipboard read timed out"
        ) ?: return

        val bodyJson = String(bodyBytes, Charsets.UTF_8)
        val payload = try {
            json.decodeFromString<ClipboardPayload>(bodyJson)
        } catch (_: Exception) {
            sendResponse(output, 400, "Bad Request", "application/json", "{\"error\":\"Invalid JSON payload\"}")
            return
        }

        // 3. Authenticate and Authorize (including Anonymous Numbered OmaID Zero-Click check)
        val headerOmaId = headers["x-omasend-omaid"]
        val candidateOmaId = when {
            !headerOmaId.isNullOrBlank() -> headerOmaId
            payload.oma_id.isNotBlank() -> payload.oma_id
            io.omarchy.omasend.crypto.OmaIdentity.isValid(payload.sender_id) -> payload.sender_id
            else -> ""
        }
        val providedPin = headers["x-omasend-pin"]
            ?: getQueryParam(query, "pin").ifBlank { null }
            ?: payload.pin

        val providedKey = headers["x-omasend-key"]
            ?: headers["authorization"]?.removePrefix("Bearer ")?.trim()
            ?: getQueryParam(query, "token").ifBlank { null }
            ?: payload.token

        val expectedPin = NetworkUtils.getDevicePin(context)
        val expectedSessionKey = NetworkUtils.getDeviceSessionKey(context)

        val isPinValid = !providedPin.isNullOrBlank() && providedPin == expectedPin
        val isKeyValid = !providedKey.isNullOrBlank() && providedKey == expectedSessionKey

        val isTokenValid = !providedKey.isNullOrBlank() &&
            pendingTransfers[providedKey]?.status == "ACCEPTED" &&
            System.currentTimeMillis() <= (pendingTransfers[providedKey]?.expiresAt ?: 0L)

        // V2-SEC-01 interim fix: claimed same-owner OmaID and claimed sender_id (both public) no longer
        // authorize clipboard writes. A paired OmaID must also come from the IP stored at pairing time.
        val isPairedPeer = isPairedOmaIdAtIp(candidateOmaId, peerIp)

        val v2 = v2Verify(headers, "POST", pathAndQuery, DeviceKey.bodyTagJson(bodyBytes), peerIp)
        if (v2 is DeviceKey.AuthResult.Rejected) {
            sendResponse(output, 401, "Unauthorized", "application/json", "{\"error\":\"Signature rejected: ${v2.reason}\"}")
            return
        }
        val isVerifiedDevice = v2 is DeviceKey.AuthResult.Trusted

        val isAuthorized = isVerifiedDevice || isPairedPeer || isPinValid || isKeyValid || isTokenValid

        if (!isAuthorized) {
            val notBlocked = recordAuthAttempt(peerIp, false)
            if (!notBlocked) {
                sendResponse(
                    output,
                    429,
                    "Too Many Requests",
                    "application/json",
                    "{\"error\":\"Too many failed authentication attempts. Please wait 60 seconds.\"}"
                )
            } else {
                sendResponse(
                    output,
                    401,
                    "Unauthorized",
                    "application/json",
                    "{\"error\":\"Unauthorized clipboard write: valid OmaID, PIN, session key, or paired token required\"}"
                )
            }
            return
        }

        // Authorized: reset failed attempts
        recordAuthAttempt(peerIp, true)

        val app = context.applicationContext as? io.omarchy.omasend.OmaSendApp
        val vault = io.omarchy.omasend.repository.ClipboardVault.getInstance(context)
        vault.processIncomingPayload(payload, peerIp, NetworkUtils.PORT, app?.client)

        Handler(Looper.getMainLooper()).post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (payload.content_type == "image" && !payload.image_hash.isNullOrBlank()) {
                    val imgFile = vault.getImageFile(payload.image_hash)
                    if (imgFile.exists() && imgFile.length() > 0) {
                        val contentUri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            imgFile
                        )
                        val clip = ClipData.newUri(context.contentResolver, "OmaSend Image", contentUri)
                        clipboard?.setPrimaryClip(clip)
                    }
                } else if (payload.text.isNotBlank()) {
                    val clip = ClipData.newPlainText("OmaSend", payload.text)
                    clipboard?.setPrimaryClip(clip)
                }
            } catch (_: Exception) {}

            try {
                app?.soundEngine?.playReceiveSound()
                app?.hapticController?.onClipboardReceived()
            } catch (_: Exception) {}

            val notifyText = if (payload.content_type == "image") "Görsel" else payload.text
            onClipboardReceived?.invoke(payload.sender_name, notifyText)
        }

        sendResponse(output, 200, "OK", "application/json", "{\"status\":\"OK\"}")
    }

    private fun handleClipboardImageDownload(output: OutputStream, headers: Map<String, String>, query: String, peerIp: String) {
        val headerOmaId = headers["x-omasend-omaid"]
        if (!NetworkUtils.isPrivateOrLocalIp(peerIp) && !isPairedOrKnownOmaId(headerOmaId)) {
            sendResponse(output, 403, "Forbidden", "application/json", "{\"error\":\"Unauthorized WAN image request\"}")
            return
        }

        val hash = getQueryParam(query, "hash")
        if (hash.isBlank() || !hash.matches(Regex("^[a-fA-F0-9]{64}$"))) {
            sendResponse(output, 400, "Bad Request", "application/json", "{\"error\":\"Invalid image hash\"}")
            return
        }

        val vault = io.omarchy.omasend.repository.ClipboardVault.getInstance(context)
        val file = vault.getImageFile(hash)
        if (!file.exists() || file.length() <= 0) {
            sendResponse(output, 404, "Not Found", "application/json", "{\"error\":\"Image not found\"}")
            return
        }

        if (file.length() > 10 * 1024 * 1024L) {
            sendResponse(output, 400, "Bad Request", "application/json", "{\"error\":\"Image exceeds 10 MiB limit\"}")
            return
        }

        try {
            val bytes = file.readBytes()
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/png\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        } catch (_: Exception) {
            sendResponse(output, 500, "Internal Server Error", "application/json", "{\"error\":\"Failed to read image\"}")
        }
    }

    private fun getQueryParam(query: String, param: String): String {
        val pairs = query.split("&")
        for (pair in pairs) {
            val parts = pair.split("=")
            if (parts.size == 2 && parts[0] == param) {
                return parts[1]
            }
        }
        return ""
    }

    private fun sendResponse(
        output: OutputStream,
        code: Int,
        statusText: String,
        contentType: String,
        body: String
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }
}
