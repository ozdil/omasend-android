package io.omarchy.omasend.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protocol v2 (Phase 1): Ed25519 device identity, QR pairing and signed requests.
 * Wire format: see PROTOCOL_V2.md in the desktop repository. Must stay byte-compatible
 * with src/devicekey.rs (checked by ProtocolV2VectorTest).
 */
object DeviceKey {
    const val PAIR_DOMAIN = "omasend-pair-v2"
    const val SIG_DOMAIN = "omasend-v2"
    const val MAX_CLOCK_SKEW_SECS = 300L
    const val PAIR_OFFER_TTL_SECS = 300L
    const val MAX_PAIR_FAILURES = 5

    private val b64Enc = Base64.getUrlEncoder().withoutPadding()
    private val b64Dec = Base64.getUrlDecoder()
    private val rng = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun b64(bytes: ByteArray): String = b64Enc.encodeToString(bytes)
    fun unb64(s: String): ByteArray? = try { b64Dec.decode(s.trim()) } catch (_: Exception) { null }
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray? = try {
        if (s.length % 2 != 0) null else ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    } catch (_: Exception) { null }
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun nowSecs(): Long = System.currentTimeMillis() / 1000
    fun randomBytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    fun ctEq(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // ------------------------------------------------------------- identity

    class Identity(seed: ByteArray) {
        private val priv = Ed25519PrivateKeyParameters(seed, 0)
        val publicKey: ByteArray = priv.generatePublicKey().encoded
        val publicB64: String = b64(publicKey)
        val deviceId: String = deviceIdForPk(publicKey)

        fun signB64(msg: ByteArray): String {
            val s = Ed25519Signer()
            s.init(true, priv)
            s.update(msg, 0, msg.size)
            return b64(s.generateSignature())
        }
    }

    fun deviceIdForPk(pk: ByteArray): String {
        val h = hex(sha256(pk).copyOfRange(0, 8)).uppercase()
        return "${h.substring(0, 4)}-${h.substring(4, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}"
    }

    fun decodePk(pkB64: String): ByteArray? = unb64(pkB64)?.takeIf { it.size == 32 }

    fun verifyB64(pk: ByteArray, msg: ByteArray, sigB64: String): Boolean {
        val sig = unb64(sigB64)?.takeIf { it.size == 64 } ?: return false
        return try {
            val v = Ed25519Signer()
            v.init(false, Ed25519PublicKeyParameters(pk, 0))
            v.update(msg, 0, msg.size)
            v.verifySignature(sig)
        } catch (_: Exception) {
            false
        }
    }

    @Volatile private var cachedIdentity: Identity? = null

    /** Seed is stored in app-private storage, wrapped with a non-exportable Android Keystore AES key. */
    fun identity(context: Context): Identity {
        cachedIdentity?.let { return it }
        synchronized(this) {
            cachedIdentity?.let { return it }
            val file = File(context.filesDir, "device_ed25519.key.enc")
            val seed = (if (file.exists()) unwrap(file.readBytes()) else null) ?: randomBytes(32).also { s ->
                wrap(s)?.let { file.writeBytes(it) }
            }
            return Identity(seed).also { cachedIdentity = it }
        }
    }

    private const val KEYSTORE_ALIAS = "omasend_device_key_wrap_v1"

    private fun wrapKey(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }
            .generateKey()
    } catch (_: Exception) {
        null
    }

    private fun wrap(seed: ByteArray): ByteArray? {
        val key = wrapKey() ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(seed)
    }

    private fun unwrap(blob: ByteArray): ByteArray? = try {
        val key = wrapKey() ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        c.doFinal(blob.copyOfRange(12, blob.size)).takeIf { it.size == 32 }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------- trusted keys

    @Serializable
    data class TrustedKey(
        val pk: String,
        val device_id: String,
        val name: String,
        val ip: String = "",
        val port: Int = 0,
        val own_device: Boolean = false,
        val added_at: Long,
        val last_seen: Long = 0
    )

    class TrustStore(context: Context) {
        private val prefs = context.applicationContext.getSharedPreferences("omasend_trusted_keys_v2", Context.MODE_PRIVATE)
        private val ser = ListSerializer(TrustedKey.serializer())

        @Synchronized fun load(): List<TrustedKey> = try {
            json.decodeFromString(ser, prefs.getString("keys", "[]") ?: "[]")
        } catch (_: Exception) { emptyList() }

        @Synchronized private fun save(keys: List<TrustedKey>) {
            prefs.edit().putString("keys", json.encodeToString(ser, keys)).apply()
        }

        fun get(pk: String): TrustedKey? = load().firstOrNull { it.pk == pk }

        @Synchronized fun upsert(key: TrustedKey) {
            save(load().filter { it.pk != key.pk } + key)
        }

        @Synchronized fun remove(pkOrDeviceId: String): Boolean {
            val before = load()
            val after = before.filter { it.pk != pkOrDeviceId && it.device_id != pkOrDeviceId }
            if (after.size != before.size) save(after)
            return after.size != before.size
        }

        @Synchronized fun touch(pk: String, ip: String) {
            val keys = load()
            val k = keys.firstOrNull { it.pk == pk } ?: return
            save(keys.map { if (it.pk == pk) k.copy(ip = ip.ifBlank { k.ip }, last_seen = nowSecs()) else it })
        }
    }

    // ------------------------------------------------------------- pairing

    fun pairMac(secret: ByteArray, responderPk: String, initiatorPk: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return hex(mac.doFinal("$PAIR_DOMAIN|$responderPk|$initiatorPk".toByteArray(Charsets.UTF_8)))
    }

    fun pairTranscript(responderPk: String, initiatorPk: String, mac: String) =
        "$PAIR_DOMAIN|$responderPk|$initiatorPk|$mac"

    data class ParsedOffer(
        val pk: String,
        val secret: ByteArray,
        val host: String,
        val port: Int,
        val name: String,
        val ownDevice: Boolean
    )

    fun isPairUri(text: String) = text.trim().startsWith("omasend://pair?")

    fun parsePairUri(uri: String): Result<ParsedOffer> = runCatching {
        val rest = uri.trim().removePrefix("omasend://pair?")
        require(rest != uri.trim()) { "Not an OmaSend pairing link" }
        val map = rest.split("&").mapNotNull { p -> p.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        require(map["v"] == "2") { "Unsupported pairing version" }
        val pk = map["pk"] ?: error("Missing pk")
        requireNotNull(decodePk(pk)) { "Invalid public key" }
        val secret = unb64(map["s"] ?: error("Missing secret"))?.takeIf { it.size == 32 } ?: error("Invalid secret")
        ParsedOffer(
            pk = pk,
            secret = secret,
            host = URLDecoder.decode(map["h"] ?: error("Missing host"), "UTF-8"),
            port = map["p"]?.toIntOrNull() ?: error("Invalid port"),
            name = URLDecoder.decode(map["n"] ?: "", "UTF-8"),
            ownDevice = map["o"] == "1"
        )
    }

    fun buildPairRequest(id: Identity, offer: ParsedOffer, myName: String): JsonObject {
        val mac = pairMac(offer.secret, offer.pk, id.publicB64)
        val sig = id.signB64(pairTranscript(offer.pk, id.publicB64, mac).toByteArray())
        return buildJsonObject {
            put("pk", id.publicB64)
            put("name", myName)
            put("mac", mac)
            put("sig", sig)
        }
    }

    fun verifyPairResponse(id: Identity, offer: ParsedOffer, resp: JsonObject): Result<Unit> = runCatching {
        val rPk = resp["pk"]?.jsonPrimitive?.content ?: error("Missing responder pk")
        require(rPk == offer.pk) { "Responder key does not match the QR code" }
        val mac = pairMac(offer.secret, offer.pk, id.publicB64)
        val sig = resp["sig"]?.jsonPrimitive?.content ?: error("Missing responder signature")
        require(verifyB64(decodePk(rPk)!!, pairTranscript(offer.pk, id.publicB64, mac).toByteArray(), sig)) { "Invalid responder signature" }
    }

    // Responder side (phone shows QR, another device scans). Offers live in memory: the
    // server runs in the same process as the UI that creates them.
    private class Offer(val secret: ByteArray, val ownDevice: Boolean, val expiresAt: Long, var failures: Int = 0)
    private val offers = mutableListOf<Offer>()

    fun createPairOffer(id: Identity, name: String, host: String, port: Int, ownDevice: Boolean): String {
        val secret = randomBytes(32)
        synchronized(offers) {
            val now = nowSecs()
            offers.removeAll { it.expiresAt <= now || it.failures >= MAX_PAIR_FAILURES }
            offers.add(Offer(secret, ownDevice, now + PAIR_OFFER_TTL_SECS))
        }
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "omasend://pair?v=2&pk=${id.publicB64}&s=${b64(secret)}&h=${enc(host)}&p=$port&n=${enc(name)}&o=${if (ownDevice) 1 else 0}"
    }

    fun acceptPairRequest(id: Identity, myName: String, body: JsonObject, peerIp: String): Result<Pair<JsonObject, TrustedKey>> = runCatching {
        val iPk = body["pk"]?.jsonPrimitive?.content ?: error("Missing pk")
        val iName = (body["name"]?.jsonPrimitive?.content ?: "Unknown device").take(64)
        val mac = body["mac"]?.jsonPrimitive?.content ?: error("Missing mac")
        val sig = body["sig"]?.jsonPrimitive?.content ?: error("Missing sig")
        val vk = decodePk(iPk) ?: error("Invalid public key")
        val offer = synchronized(offers) {
            val now = nowSecs()
            offers.removeAll { it.expiresAt <= now || it.failures >= MAX_PAIR_FAILURES }
            val match = offers.firstOrNull { ctEq(pairMac(it.secret, id.publicB64, iPk).toByteArray(), mac.toByteArray()) }
            if (match == null) {
                offers.forEach { it.failures++ }
                error("No matching pairing offer (wrong or expired QR code)")
            }
            if (!verifyB64(vk, pairTranscript(id.publicB64, iPk, mac).toByteArray(), sig)) {
                match.failures++
                error("Invalid initiator signature")
            }
            offers.remove(match)
            match
        }
        val now = nowSecs()
        val key = TrustedKey(iPk, deviceIdForPk(vk), iName, peerIp, 0, offer.ownDevice, now, now)
        val resp = buildJsonObject {
            put("pk", id.publicB64)
            put("name", myName)
            put("device_id", id.deviceId)
            put("sig", id.signB64(pairTranscript(id.publicB64, iPk, mac).toByteArray()))
        }
        resp to key
    }

    // ------------------------------------------------------------- signed requests

    fun bodyTagJson(body: ByteArray): String = hex(sha256(body))
    fun bodyTagStream(contentLength: Long): String = "stream:$contentLength"

    fun canonical(method: String, pathAndQuery: String, ts: Long, nonce: String, bodyTag: String) =
        "$SIG_DOMAIN\n$method\n$pathAndQuery\n$ts\n$nonce\n$bodyTag"

    fun authHeaders(id: Identity, method: String, pathAndQuery: String, bodyTag: String): Map<String, String> {
        val ts = nowSecs()
        val nonce = hex(randomBytes(16))
        return mapOf(
            "X-OmaSend-Pk" to id.publicB64,
            "X-OmaSend-Ts" to ts.toString(),
            "X-OmaSend-Nonce" to nonce,
            "X-OmaSend-Sig" to id.signB64(canonical(method, pathAndQuery, ts, nonce, bodyTag).toByteArray())
        )
    }

    sealed class AuthResult {
        object Absent : AuthResult()
        data class Trusted(val key: TrustedKey) : AuthResult()
        data class Rejected(val reason: String) : AuthResult()
    }

    private val seenNonces = ConcurrentHashMap<String, Long>()

    /** Convenience wrapper for the server: verifies and records the peer's current IP. */
    fun verifyRequest(store: TrustStore, headers: Map<String, String>, method: String, pathAndQuery: String, bodyTag: String, peerIp: String): AuthResult {
        val r = verifyRequest({ store.get(it) }, headers, method, pathAndQuery, bodyTag)
        if (r is AuthResult.Trusted) store.touch(r.key.pk, peerIp)
        return r
    }

    /** [headers] must use lowercase names. Unknown keys are reported as Absent (legacy rules apply). */
    fun verifyRequest(lookup: (String) -> TrustedKey?, headers: Map<String, String>, method: String, pathAndQuery: String, bodyTag: String): AuthResult {
        val pk = headers["x-omasend-pk"]
        val ts = headers["x-omasend-ts"]
        val nonce = headers["x-omasend-nonce"]
        val sig = headers["x-omasend-sig"]
        if (pk == null && sig == null) return AuthResult.Absent
        if (pk == null || ts == null || nonce == null || sig == null) return AuthResult.Rejected("Incomplete signature headers")
        val tsVal = ts.trim().toLongOrNull() ?: return AuthResult.Rejected("Bad timestamp")
        val now = nowSecs()
        if (kotlin.math.abs(tsVal - now) > MAX_CLOCK_SKEW_SECS) return AuthResult.Rejected("Timestamp outside allowed window")
        if (nonce.length != 32 || !nonce.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return AuthResult.Rejected("Bad nonce")
        val trusted = lookup(pk.trim()) ?: return AuthResult.Absent
        val vk = decodePk(trusted.pk) ?: return AuthResult.Rejected("Corrupt stored key")
        if (!verifyB64(vk, canonical(method, pathAndQuery, tsVal, nonce, bodyTag).toByteArray(), sig)) return AuthResult.Rejected("Invalid signature")
        seenNonces.entries.removeIf { it.value <= now }
        if (seenNonces.putIfAbsent("${trusted.pk}:$nonce", now + 2 * MAX_CLOCK_SKEW_SECS + 1) != null) return AuthResult.Rejected("Replayed request")
        return AuthResult.Trusted(trusted)
    }
}
