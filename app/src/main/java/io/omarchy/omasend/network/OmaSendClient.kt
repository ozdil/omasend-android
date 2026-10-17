package io.omarchy.omasend.network

import android.content.Context
import io.omarchy.omasend.crypto.DeviceKey
import io.omarchy.omasend.model.ClipboardPayload
import io.omarchy.omasend.model.TransferDecision
import io.omarchy.omasend.model.TransferFileInfo
import io.omarchy.omasend.model.TransferRequest
import io.omarchy.omasend.model.TransferResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

private class HighPerfSocketFactory(
    private val delegate: SocketFactory = SocketFactory.getDefault()
) : SocketFactory() {
    override fun createSocket(): Socket {
        return delegate.createSocket().also { NetworkUtils.configureHighThroughputSocket(it) }
    }

    override fun createSocket(host: String, port: Int): Socket {
        return delegate.createSocket(host, port).also { NetworkUtils.configureHighThroughputSocket(it) }
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        return delegate.createSocket(host, port, localHost, localPort).also { NetworkUtils.configureHighThroughputSocket(it) }
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        return delegate.createSocket(host, port).also { NetworkUtils.configureHighThroughputSocket(it) }
    }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        return delegate.createSocket(address, port, localAddress, localPort).also { NetworkUtils.configureHighThroughputSocket(it) }
    }
}

class OmaSendClient(private val context: Context) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    /**
     * Protocol v2: signs every outgoing request with this device's Ed25519 key.
     * Uploads sign "stream:<length>" so multi-GB bodies are not buffered.
     */
    private val v2SigningInterceptor = Interceptor { chain ->
        val req = chain.request()
        val url = req.url
        val pathAndQuery = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val body = req.body
        val tag = if (url.encodedPath == "/api/p2p/upload") {
            DeviceKey.bodyTagStream(body?.contentLength() ?: 0L)
        } else {
            val buf = okio.Buffer()
            body?.writeTo(buf)
            DeviceKey.bodyTagJson(buf.readByteArray())
        }
        // If the device key is temporarily unreadable, send unsigned: the receiver applies the
        // legacy rules, which is what this HTTP path is for anyway (paired devices use QUIC).
        val id = DeviceKey.identityOrNull(context)
        if (id == null) {
            chain.proceed(req)
        } else {
            val signed = req.newBuilder()
            DeviceKey.authHeaders(id, req.method, pathAndQuery, tag).forEach { (k, v) -> signed.header(k, v) }
            chain.proceed(signed.build())
        }
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(v2SigningInterceptor)
        .socketFactory(HighPerfSocketFactory())
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Dedicated upload client with unlimited read/write timeout for multi-gigabyte file streaming
    private val uploadClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private fun getLocalOmaId(): String {
        return try {
            val app = context.applicationContext as? io.omarchy.omasend.OmaSendApp
            app?.omaIdentity?.formattedId ?: io.omarchy.omasend.crypto.OmaIdentity.getOrGenerate(context).formattedId
        } catch (_: Exception) {
            ""
        }
    }

    private fun isAllowedTarget(targetIp: String, targetOmaId: String? = null, hasActiveToken: Boolean = false): Boolean {
        if (NetworkUtils.isPrivateOrLocalIp(targetIp)) return true
        if (hasActiveToken) return true
        if (!targetOmaId.isNullOrBlank() && io.omarchy.omasend.crypto.OmaIdentity.isValid(targetOmaId)) return true
        val localOma = getLocalOmaId()
        if (localOma.isNotBlank() && io.omarchy.omasend.crypto.OmaIdentity.isValid(localOma)) {
            val app = context.applicationContext as? io.omarchy.omasend.OmaSendApp
            val knownWanPeer = app?.discoveryManager?.peers?.value?.any { it.ip == targetIp } == true ||
                app?.wanDiscoveryEngine?.status?.value?.rendezvousPeers?.any { it.ip == targetIp } == true
            if (knownWanPeer) return true
        }
        return false
    }

    /**
     * Protocol v2 initiator: pair with the device whose QR code (omasend://pair?...) was scanned.
     * The responder's key is pinned from the QR code, so a LAN attacker cannot impersonate it.
     */
    fun pairWithUri(uri: String): Result<DeviceKey.TrustedKey> = runCatching {
        val offer = DeviceKey.parsePairUri(uri).getOrThrow()
        require(NetworkUtils.isPrivateOrLocalIp(offer.host)) { "Pairing is only allowed on the local network" }
        val id = DeviceKey.identity(context)
        val myQuic = CoreNode.advertisedQuicPort()
        val body = DeviceKey.buildPairRequest(id, offer, NetworkUtils.getDeviceName(context), myQuic).toString()
        val request = Request.Builder()
            .url("http://${offer.host}:${offer.port}/api/v2/pair")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string().ifEmpty { error("Empty response") }
            val obj = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject ?: error("Malformed response")
            if (!response.isSuccessful) {
                error((obj["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "Pairing rejected (${response.code})")
            }
            DeviceKey.verifyPairResponse(id, offer, obj).getOrThrow()
            val name = ((obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: offer.name).ifBlank { offer.name }.take(64)
            val now = DeviceKey.nowSecs()
            val key = DeviceKey.TrustedKey(
                pk = offer.pk,
                device_id = DeviceKey.deviceIdForPk(DeviceKey.decodePk(offer.pk)!!),
                name = name,
                ip = offer.host,
                port = offer.port,
                quic_port = (obj["quic_port"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                    ?.takeIf { it in 1..65535 } ?: offer.quicPort,
                own_device = offer.ownDevice,
                added_at = now,
                last_seen = now
            )
            DeviceKey.TrustStore(context).upsert(key)
            key
        }
    }

    private fun postJson(url: String, body: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
        val request = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute().use { response ->
            val text = response.body.string().ifEmpty { error("Empty response") }
            val obj = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject ?: error("Malformed response")
            if (!response.isSuccessful) {
                error((obj["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "Rejected (${response.code})")
            }
            obj
        }
    }

    private fun linkOne(host: String, port: Int, accountId: String): Result<DeviceKey.TrustedKey> = runCatching {
        val base = "http://$host:$port/api/v2/link"
        val ini = io.omarchy.omasend.crypto.OmaLink.Initiator(accountId)
        val pA = DeviceKey.b64(ini.pA)
        val start = postJson("$base/start", kotlinx.serialization.json.buildJsonObject {
            put("v", kotlinx.serialization.json.JsonPrimitive(1))
            put("pA", kotlinx.serialization.json.JsonPrimitive(pA))
        })
        fun field(k: String) = (start[k] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: error("Missing $k")
        val pB = field("pB")
        // Fails here for every device on another account; nothing more is sent.
        val keys = ini.finish(DeviceKey.unb64(pB) ?: error("Invalid pB"), DeviceKey.unb64(field("cB")) ?: error("Invalid cB")).getOrThrow()
        val id = DeviceKey.identity(context)
        val myQuic = CoreNode.advertisedQuicPort()
        val reply = postJson("$base/finish", kotlinx.serialization.json.buildJsonObject {
            put("sid", kotlinx.serialization.json.JsonPrimitive(field("sid")))
            put("cA", kotlinx.serialization.json.JsonPrimitive(DeviceKey.b64(keys.cA)))
            put("box", kotlinx.serialization.json.JsonPrimitive(
                io.omarchy.omasend.crypto.OmaLink.initiatorBox(id, keys, pA, pB, NetworkUtils.getDeviceName(context), myQuic)
            ))
        })
        val sealed = (reply["box"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: error("Missing box")
        val peer = io.omarchy.omasend.crypto.OmaLink.openResponderBox(keys, pA, pB, id.publicB64, sealed).getOrThrow()
        require(!io.omarchy.omasend.crypto.OmaLink.isRevoked(context, peer.pk)) { "That device was removed from the account" }
        val now = DeviceKey.nowSecs()
        val key = DeviceKey.TrustedKey(
            pk = peer.pk,
            device_id = DeviceKey.deviceIdForPk(DeviceKey.decodePk(peer.pk)!!),
            name = peer.name,
            ip = host,
            port = port,
            quic_port = peer.quicPort,
            own_device = true,
            added_at = now,
            last_seen = now
        )
        DeviceKey.TrustStore(context).upsert(key)
        key
    }

    private val rosterScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /**
     * Sends our own-device list and revocations to every own device we can address (and to
     * nearby revoked devices, so they leave the account). Best effort, in the background.
     */
    fun pushRoster() {
        rosterScope.launch {
            val me = DeviceKey.identityOrNull(context) ?: return@launch
            val app = context.applicationContext as? io.omarchy.omasend.OmaSendApp
            val store = DeviceKey.TrustStore(context)
            val revoked = io.omarchy.omasend.crypto.OmaLink.revoked(context)
            val own = store.load().filter { it.own_device }
            val body = io.omarchy.omasend.crypto.OmaLink.rosterBody(
                me,
                NetworkUtils.getDeviceName(context),
                NetworkUtils.PORT,
                CoreNode.advertisedQuicPort(),
                own,
                revoked
            )
            val nearby = app?.discoveryManager?.peers?.value.orEmpty()
            val targets = own.mapNotNull { k ->
                val seen = nearby.firstOrNull { it.pk == k.pk }
                val ip = seen?.ip ?: k.ip
                val port = seen?.port ?: k.port.takeIf { it != 0 } ?: NetworkUtils.PORT
                if (ip.isBlank()) null else ip to port
            } + nearby.filter { it.pk in revoked }.map { it.ip to it.port }
            targets.distinct().map { (ip, port) ->
                async {
                    runCatching {
                        val request = Request.Builder()
                            .url("http://$ip:$port/api/v2/account/sync")
                            .post(body.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                        client.newCall(request).execute().close()
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * "Link with OmaID": tries every nearby, not yet trusted v2 device in parallel. Only devices
     * holding [accountId] complete the exchange. On any success the number becomes this
     * device's account OmaID too. Returns the linked devices (empty if none matched).
     */
    suspend fun linkWithAccountId(accountId: String, peers: List<io.omarchy.omasend.model.DiscoveredPeer>): Result<List<DeviceKey.TrustedKey>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                require(io.omarchy.omasend.crypto.OmaIdentity.isValid(accountId)) { "Invalid OmaID" }
                val myPk = DeviceKey.identity(context).publicB64
                val known = DeviceKey.TrustStore(context).load().map { it.pk }.toSet()
                val revoked = io.omarchy.omasend.crypto.OmaLink.revoked(context).toSet()
                val targets = peers.filter {
                    it.pk.isNotBlank() && it.pk != myPk && it.pk !in known && it.pk !in revoked && NetworkUtils.isPrivateOrLocalIp(it.ip)
                }.distinctBy { it.pk }
                val linked = kotlinx.coroutines.coroutineScope {
                    targets.map { p -> async { linkOne(p.ip, p.port, accountId).getOrNull() } }.awaitAll().filterNotNull()
                }
                if (linked.isNotEmpty()) {
                    io.omarchy.omasend.crypto.OmaLink.setAccountId(context, accountId)
                    pushRoster()
                }
                linked
            }
        }

    fun sendTransferRequest(
        targetIp: String,
        targetPort: Int,
        files: List<TransferFileInfo>,
        targetOmaId: String = ""
    ): Result<String> {
        return sendTransferRequestWithStatus(targetIp, targetPort, files, targetOmaId).map { it.first }
    }

    /**
     * Sends transfer request and returns Pair(token, status).
     * When status == "ACCEPTED", Zero-Click Auto-Accept was triggered on the target peer.
     */
    fun sendTransferRequestWithStatus(
        targetIp: String,
        targetPort: Int,
        files: List<TransferFileInfo>,
        targetOmaId: String = ""
    ): Result<Pair<String, String>> {
        if (!isAllowedTarget(targetIp, targetOmaId)) {
            return Result.failure(SecurityException("Target IP is outside private network scope and not an authenticated OmaID session"))
        }
        return try {
            val myOmaId = getLocalOmaId()
            val totalBytes = files.sumOf { it.size_bytes }
            val requestPayload = TransferRequest(
                sender_id = NetworkUtils.getDeviceId(context),
                sender_name = NetworkUtils.getDeviceName(context),
                sender_ip = NetworkUtils.getLocalIpAddress(),
                files = files,
                total_size_bytes = totalBytes,
                oma_id = myOmaId
            )
            val jsonBody = json.encodeToString(TransferRequest.serializer(), requestPayload)
            val requestBuilder = Request.Builder()
                .url("http://$targetIp:$targetPort/api/p2p/request")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))

            if (myOmaId.isNotBlank()) {
                requestBuilder.header("X-OmaSend-OmaID", myOmaId)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val respBody = response.body.string().ifEmpty { return Result.failure(Exception("Empty response")) }
                val transferResp = json.decodeFromString<TransferResponse>(respBody)

                if (transferResp.token != null) {
                    Result.success(Pair(transferResp.token, transferResp.status))
                } else {
                    Result.failure(Exception(transferResp.error ?: "Peer rejected transfer request"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Volatile
    private var activeUploadCall: okhttp3.Call? = null

    @Volatile
    private var cancelRequested: Boolean = false

    fun cancelActiveTransfer() {
        cancelRequested = true
        try {
            activeUploadCall?.cancel()
        } catch (_: Exception) {}
    }

    suspend fun pollDecision(
        targetIp: String,
        targetPort: Int,
        token: String,
        timeoutSec: Int = 30
    ): Result<Boolean> {
        if (!isAllowedTarget(targetIp, hasActiveToken = token.isNotBlank())) {
            return Result.failure(SecurityException("Target IP is outside private network scope"))
        }
        cancelRequested = false
        val myOmaId = getLocalOmaId()
        val deadline = System.currentTimeMillis() + (timeoutSec * 1000L)
        while (System.currentTimeMillis() < deadline) {
            if (cancelRequested) {
                return Result.failure(Exception("Transfer kullanıcı tarafından iptal edildi."))
            }
            try {
                val reqBuilder = Request.Builder()
                    .url("http://$targetIp:$targetPort/api/p2p/decision?token=$token")
                    .get()
                if (myOmaId.isNotBlank()) {
                    reqBuilder.header("X-OmaSend-OmaID", myOmaId)
                }

                val decision = client.newCall(reqBuilder.build()).execute().use { response ->
                    val respBody = response.body.string()
                    json.decodeFromString<TransferDecision>(respBody)
                }

                when (decision.status) {
                    "ACCEPTED" -> return Result.success(true)
                    "REJECTED" -> return Result.failure(Exception("Transfer was declined by recipient."))
                    "EXPIRED" -> return Result.failure(Exception("Transfer request expired."))
                    "NOT_FOUND" -> return Result.failure(Exception("The recipient no longer has this transfer request."))
                    "" -> if (decision.error != null) return Result.failure(Exception(decision.error))
                    else -> {
                        // Still PENDING, wait and poll again
                    }
                }
            } catch (_: Exception) {
            }
            delay(600)
        }
        return Result.failure(Exception("Transfer timed out waiting for approval."))
    }

    fun uploadFileStream(
        targetIp: String,
        targetPort: Int,
        token: String,
        filename: String,
        totalBytes: Long,
        inputStream: InputStream,
        onProgress: (bytesWritten: Long, totalBytes: Long, percent: Int) -> Unit
    ): Result<Unit> {
        return uploadFileStreamWithMetrics(
            targetIp = targetIp,
            targetPort = targetPort,
            token = token,
            filename = filename,
            totalBytes = totalBytes,
            inputStream = inputStream
        ) { written, total, pct, _, _ ->
            onProgress(written, total, pct)
        }
    }

    fun uploadFileStreamWithMetrics(
        targetIp: String,
        targetPort: Int,
        token: String,
        filename: String,
        totalBytes: Long,
        inputStream: InputStream,
        onProgress: (bytesWritten: Long, totalBytes: Long, percent: Int, speedMBps: Double, etaSeconds: Long) -> Unit
    ): Result<Unit> {
        if (!isAllowedTarget(targetIp, hasActiveToken = token.isNotBlank())) {
            try { inputStream.close() } catch (_: Exception) {}
            return Result.failure(SecurityException("Target IP is outside private network scope"))
        }
        cancelRequested = false
        val myOmaId = getLocalOmaId()
        val speedCalculator = TransferSpeedCalculator(totalBytes.coerceAtLeast(1L))
        val wifiLock = NetworkUtils.acquireHighPerfWifiLock(context, "OmaSend:ClientUpload")
        return try {
            val encodedName = URLEncoder.encode(filename, "UTF-8")
            val countingBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = totalBytes

                override fun writeTo(sink: BufferedSink) {
                    val buffer = ByteArray(NetworkUtils.IO_CHUNK_SIZE)
                    var uploaded = 0L
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        if (cancelRequested) {
                            throw java.io.InterruptedIOException("Transfer kullanıcı tarafından iptal edildi.")
                        }
                        sink.write(buffer, 0, read)
                        uploaded += read
                        val metrics = speedCalculator.update(uploaded)
                        onProgress(uploaded, totalBytes, metrics.percent, metrics.speedMBps, metrics.etaSeconds)
                    }
                    sink.flush()
                }
            }

            val requestBuilder = Request.Builder()
                .url("http://$targetIp:$targetPort/api/p2p/upload?token=$token&filename=$encodedName")
                .post(countingBody)
            if (myOmaId.isNotBlank()) {
                requestBuilder.header("X-OmaSend-OmaID", myOmaId)
            }

            val call = uploadClient.newCall(requestBuilder.build())
            activeUploadCall = call
            call.execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Upload failed with HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            if (cancelRequested) {
                Result.failure(Exception("Transfer kullanıcı tarafından iptal edildi."))
            } else {
                Result.failure(e)
            }
        } finally {
            activeUploadCall = null
            NetworkUtils.releaseWifiLock(wifiLock)
            try {
                inputStream.close()
            } catch (_: Exception) {
            }
        }
    }

    fun sendClipboard(
        targetIp: String,
        targetPort: Int,
        text: String,
        targetOmaId: String = ""
    ): Result<Unit> {
        if (!isAllowedTarget(targetIp, targetOmaId)) {
            return Result.failure(SecurityException("Target IP is outside private network scope"))
        }
        return try {
            val myOmaId = getLocalOmaId()
            val payload = ClipboardPayload(
                sender_id = NetworkUtils.getDeviceId(context),
                sender_name = NetworkUtils.getDeviceName(context),
                text = text,
                pin = null,
                token = null,
                oma_id = myOmaId
            )
            val jsonBody = json.encodeToString(ClipboardPayload.serializer(), payload)
            val reqBuilder = Request.Builder()
                .url("http://$targetIp:$targetPort/api/p2p/clipboard")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
            if (myOmaId.isNotBlank()) {
                reqBuilder.header("X-OmaSend-OmaID", myOmaId)
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Clipboard sync failed with HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun sendClipboardPayload(
        targetIp: String,
        targetPort: Int,
        payload: ClipboardPayload,
        targetOmaId: String = ""
    ): Result<Unit> {
        if (!isAllowedTarget(targetIp, targetOmaId)) {
            return Result.failure(SecurityException("Target IP is outside private network scope"))
        }
        return try {
            val myOmaId = getLocalOmaId()
            val fullPayload = payload.copy(
                sender_id = NetworkUtils.getDeviceId(context),
                sender_name = NetworkUtils.getDeviceName(context),
                pin = null,
                token = null,
                oma_id = myOmaId
            )
            val jsonBody = json.encodeToString(ClipboardPayload.serializer(), fullPayload)
            val reqBuilder = Request.Builder()
                .url("http://$targetIp:$targetPort/api/p2p/clipboard")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
            if (myOmaId.isNotBlank()) {
                reqBuilder.header("X-OmaSend-OmaID", myOmaId)
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Clipboard sync failed with HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun fetchClipboardImage(
        targetIp: String,
        targetPort: Int,
        hash: String,
        targetOmaId: String = ""
    ): Result<ByteArray> {
        if (!isAllowedTarget(targetIp, targetOmaId, hasActiveToken = true)) {
            return Result.failure(SecurityException("Target IP is outside private network scope"))
        }
        // The hash comes from the network: only a 64-char hex digest may go into the URL.
        val cleanHash = io.omarchy.omasend.engine.ImageHash.normalize(hash)
            ?: return Result.failure(IllegalArgumentException("Invalid image hash"))
        return try {
            val myOmaId = getLocalOmaId()
            val reqBuilder = Request.Builder()
                .url("http://$targetIp:$targetPort/api/p2p/clipboard/image?hash=$cleanHash")
                .get()
            if (myOmaId.isNotBlank()) {
                reqBuilder.header("X-OmaSend-OmaID", myOmaId)
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(Exception("HTTP ${response.code} fetching clipboard image"))
                }
                val body = response.body
                val bytes = body.bytes()
                if (bytes.size > 10 * 1024 * 1024) {
                    return Result.failure(Exception("Image exceeds 10 MiB limit"))
                }
                if (!io.omarchy.omasend.engine.ImageHash.matches(bytes, cleanHash)) {
                    return Result.failure(SecurityException("Clipboard image does not match its hash"))
                }
                Result.success(bytes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
