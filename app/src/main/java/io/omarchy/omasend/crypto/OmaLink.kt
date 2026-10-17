package io.omarchy.omasend.crypto

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * "Link with OmaID": add your own devices by typing the secret account number.
 * SPAKE2 over P-256, byte-compatible with src/omalink.rs in the desktop repository
 * (spec: PROTOCOL_V2.md "Linking with an OmaID"; vectors in OmaLinkTest).
 *
 * The account OmaID never leaves the device. A wrong number can only be tried online,
 * one guess per exchange, and responders budget those guesses.
 */
object OmaLink {
    const val DOMAIN = "omasend-link-v1"
    const val SESSION_TTL_MS = 30_000L
    const val MAX_SESSIONS = 32
    const val RATE_WINDOW_MS = 600_000L
    const val MAX_ATTEMPTS_PER_IP = 5
    const val MAX_ATTEMPTS_TOTAL = 20

    private val curve = CustomNamedCurves.getByName("secp256r1")
    private val n: BigInteger = curve.n
    private val g: ECPoint = curve.g
    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------- account OmaID

    private const val ACCOUNT_FILE = "account_id.enc"
    private const val ACCOUNT_FILE_PLAIN = "account_id"

    /** The secret account OmaID, created on first use. Never put this on the wire. */
    @Synchronized
    fun accountId(context: Context): String {
        val dir = context.filesDir
        val enc = File(dir, ACCOUNT_FILE)
        val plain = File(dir, ACCOUNT_FILE_PLAIN)
        val wrapped = if (enc.exists()) DeviceKey.unwrapSecret(enc.readBytes())?.toString(Charsets.UTF_8) else null
        if (wrapped != null && OmaIdentity.isValid(wrapped)) return OmaIdentity.format(wrapped)
        val legacyPlain = if (plain.exists()) plain.readText() else null
        if (legacyPlain != null && OmaIdentity.isValid(legacyPlain)) {
            // Migrate a plaintext account number into Keystore-wrapped storage when possible
            // (writeAccountId deletes the plaintext file once the wrapped copy is written).
            return writeAccountId(context, legacyPlain)
        }
        return writeAccountId(context, OmaIdentity.generateRandomOmaId())
    }

    /** Replaces the account number. Devices already linked stay trusted. */
    fun newAccountId(context: Context): String = writeAccountId(context, OmaIdentity.generateRandomOmaId())

    @Synchronized
    fun setAccountId(context: Context, input: String): String {
        require(OmaIdentity.isValid(input)) { "Invalid OmaID" }
        return writeAccountId(context, input)
    }

    @Synchronized
    private fun writeAccountId(context: Context, id: String): String {
        val formatted = OmaIdentity.format(id)
        val wrapped = DeviceKey.wrap(formatted.toByteArray(Charsets.UTF_8))
        val enc = File(context.filesDir, ACCOUNT_FILE)
        val plain = File(context.filesDir, ACCOUNT_FILE_PLAIN)
        if (wrapped != null) {
            enc.writeBytes(wrapped)
            plain.delete()
        } else {
            plain.writeText(formatted)
            enc.delete()
        }
        return formatted
    }

    // ------------------------------------------------------------- SPAKE2

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = OmaIdentity.hmacSha256(key, data)

    internal fun decodePoint(bytes: ByteArray): ECPoint? = try {
        curve.curve.decodePoint(bytes).normalize().takeIf { !it.isInfinity && it.isValid }
    } catch (_: Exception) {
        null
    }

    internal fun encode(p: ECPoint): ByteArray = p.normalize().getEncoded(true)

    private fun hashToPoint(seed: String): ECPoint {
        var ctr = 0
        while (true) {
            val x = sha256(seed.toByteArray(), ByteBuffer.allocate(4).putInt(ctr).array())
            decodePoint(byteArrayOf(0x02) + x)?.let { return it }
            ctr++
        }
    }

    internal val M: ECPoint by lazy { hashToPoint("omasend-link-v1 M") }
    internal val N: ECPoint by lazy { hashToPoint("omasend-link-v1 N") }

    internal fun scalarBytes(s: BigInteger): ByteArray {
        val raw = s.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    internal fun passwordScalar(omaId: String): BigInteger =
        BigInteger(1, sha256("$DOMAIN|w|${omaId.filter { it.isDigit() }}".toByteArray())).mod(n)

    private fun randomScalar(): BigInteger {
        while (true) {
            val s = BigInteger(1, DeviceKey.randomBytes(32)).mod(n)
            if (s.signum() != 0) return s
        }
    }

    class Keys(val cA: ByteArray, val cB: ByteArray, val boxKey: ByteArray)

    private fun deriveKeys(pA: ByteArray, pB: ByteArray, k: ECPoint, w: BigInteger): Keys {
        require(!k.isInfinity) { "Degenerate key exchange" }
        val buf = java.io.ByteArrayOutputStream()
        for (part in listOf(DOMAIN.toByteArray(), pA, pB, encode(k), scalarBytes(w))) {
            buf.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(part.size.toLong()).array())
            buf.write(part)
        }
        val tt = sha256(buf.toByteArray())
        val ke = tt.copyOfRange(0, 16)
        val ka = tt.copyOfRange(16, 32)
        val kcA = hmac(ka, "omasend-link-v1 KcA".toByteArray())
        val kcB = hmac(ka, "omasend-link-v1 KcB".toByteArray())
        return Keys(
            cA = hmac(kcA, pA + pB),
            cB = hmac(kcB, pB + pA),
            boxKey = hmac(ke, "omasend-link-v1 box".toByteArray())
        )
    }

    /** The device where the user typed the number. */
    class Initiator internal constructor(omaId: String, private val x: BigInteger) {
        constructor(omaId: String) : this(omaId, randomScalar())

        private val w = passwordScalar(omaId)
        val pA: ByteArray = encode(g.multiply(x).add(M.multiply(w)))

        /** Checks the responder's confirmation; fails for a device on another account. */
        fun finish(pB: ByteArray, cB: ByteArray): Result<Keys> = runCatching {
            val pbPt = decodePoint(pB) ?: error("Invalid responder point")
            val k = pbPt.subtract(N.multiply(w)).multiply(x)
            val keys = deriveKeys(pA, pB, k, w)
            require(DeviceKey.ctEq(keys.cB, cB)) { "OmaID does not match" }
            keys
        }
    }

    fun respond(omaId: String, pA: ByteArray): Result<Pair<ByteArray, Keys>> = respond(omaId, pA, randomScalar())

    internal fun respond(omaId: String, pA: ByteArray, y: BigInteger): Result<Pair<ByteArray, Keys>> = runCatching {
        val paPt = decodePoint(pA) ?: error("Invalid initiator point")
        val w = passwordScalar(omaId)
        val pB = encode(g.multiply(y).add(N.multiply(w)))
        val k = paPt.subtract(M.multiply(w)).multiply(y)
        pB to deriveKeys(pA, pB, k, w)
    }

    // ------------------------------------------------------------- identity boxes

    fun seal(boxKey: ByteArray, dir: String, plain: ByteArray): String {
        val iv = DeviceKey.randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(boxKey, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD("$DOMAIN $dir".toByteArray())
        return DeviceKey.b64(iv + c.doFinal(plain))
    }

    fun open(boxKey: ByteArray, dir: String, sealed: String): Result<ByteArray> = runCatching {
        val raw = DeviceKey.unb64(sealed) ?: error("Invalid box encoding")
        require(raw.size >= 12 + 16) { "Box too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(boxKey, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        c.updateAAD("$DOMAIN $dir".toByteArray())
        try {
            c.doFinal(raw.copyOfRange(12, raw.size))
        } catch (_: Exception) {
            error("Box authentication failed")
        }
    }

    fun sigTranscript(pA: String, pB: String, iPk: String, rPk: String? = null): String =
        if (rPk == null) "$DOMAIN|$pA|$pB|$iPk" else "$DOMAIN|$pA|$pB|$iPk|$rPk"

    fun initiatorBox(id: DeviceKey.Identity, keys: Keys, pA: String, pB: String, name: String, quicPort: Int): String {
        val body = buildJsonObject {
            put("pk", id.publicB64)
            put("name", name)
            put("quic_port", quicPort)
            put("sig", id.signB64(sigTranscript(pA, pB, id.publicB64).toByteArray()))
        }
        return seal(keys.boxKey, "I", body.toString().toByteArray())
    }

    data class LinkedPeer(val pk: String, val name: String, val quicPort: Int)

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    private fun parseIdentity(plain: ByteArray, transcript: (String) -> String): LinkedPeer {
        val o = json.parseToJsonElement(String(plain, Charsets.UTF_8)).jsonObject
        val pk = str(o, "pk") ?: error("Missing pk")
        val sig = str(o, "sig") ?: error("Missing sig")
        val raw = DeviceKey.decodePk(pk) ?: error("Invalid public key")
        require(DeviceKey.verifyB64(raw, transcript(pk).toByteArray(), sig)) { "Invalid device signature" }
        return LinkedPeer(
            pk = pk,
            name = (str(o, "name") ?: "Unknown device").take(64),
            quicPort = (o["quic_port"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..65535 } ?: 0
        )
    }

    fun openResponderBox(keys: Keys, pA: String, pB: String, myPk: String, sealed: String): Result<LinkedPeer> = runCatching {
        val plain = open(keys.boxKey, "R", sealed).getOrThrow()
        parseIdentity(plain) { rPk -> sigTranscript(pA, pB, myPk, rPk) }
    }

    // ------------------------------------------------------------- roster and removal
    //
    // Linked devices push their own-device list to each other (signed request to
    // /api/v2/account/sync), so linking with one device joins you to all of them. Removing an
    // own device revokes its key everywhere, and every device that learns of a new revocation
    // replaces its account number. Byte-compatible with src/omalink.rs.

    const val MAX_ROSTER = 64
    const val MAX_REVOKED = 256
    private const val REVOKED_PREFS = "omasend_revoked_keys"

    fun revoked(context: Context): List<String> = try {
        val raw = context.getSharedPreferences(REVOKED_PREFS, Context.MODE_PRIVATE).getString("keys", null)
        if (raw == null) emptyList() else json.parseToJsonElement(raw).jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    } catch (_: Exception) {
        emptyList()
    }

    private fun saveRevoked(context: Context, list: List<String>) {
        val tail = list.takeLast(MAX_REVOKED)
        context.getSharedPreferences(REVOKED_PREFS, Context.MODE_PRIVATE).edit()
            .putString("keys", JsonArray(tail.map { JsonPrimitive(it) }).toString())
            .apply()
    }

    fun isRevoked(context: Context, pk: String) = pk in revoked(context)

    /** Removes one of our own devices everywhere. The caller then pushes the roster. */
    @Synchronized
    fun revokeOwnDevice(context: Context, pk: String) {
        val list = revoked(context)
        if (pk !in list) saveRevoked(context, list + pk)
        DeviceKey.TrustStore(context).remove(pk)
        newAccountId(context)
    }

    fun rosterBody(me: DeviceKey.Identity, myName: String, myPort: Int, myQuic: Int, own: List<DeviceKey.TrustedKey>, revoked: List<String>): JsonObject {
        val devices = buildList {
            add(buildJsonObject { put("pk", me.publicB64); put("name", myName); put("port", myPort); put("quic_port", myQuic) })
            own.filter { it.own_device }.forEach { k ->
                add(buildJsonObject { put("pk", k.pk); put("name", k.name); put("ip", k.ip); put("port", k.port); put("quic_port", k.quic_port) })
            }
        }.take(MAX_ROSTER)
        return buildJsonObject {
            put("v", 1)
            put("devices", JsonArray(devices))
            put("revoked", JsonArray(revoked.map { JsonPrimitive(it) }))
        }
    }

    /** What applying a roster changes; computed without touching storage so it can be tested. */
    data class RosterPlan(
        val revoked: List<String>,
        val remove: List<String>,
        val upsert: List<DeviceKey.TrustedKey>,
        val added: List<String>,
        val selfRevoked: Boolean,
        val rotate: Boolean
    )

    fun planRoster(
        myPk: String,
        sender: DeviceKey.TrustedKey,
        senderIp: String,
        body: JsonObject,
        revoked: List<String>,
        existing: List<DeviceKey.TrustedKey>,
        now: Long
    ): Result<RosterPlan> = runCatching {
        require(sender.own_device) { "Only your own devices can share the device list" }
        require((body["v"] as? JsonPrimitive)?.intOrNull == 1) { "Unsupported roster" }
        val allRevoked = revoked.toMutableList()
        val remove = mutableListOf<String>()
        var selfRevoked = false
        var newly = false
        (body["revoked"] as? JsonArray).orEmpty().take(MAX_REVOKED).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.forEach { pk ->
            if (DeviceKey.decodePk(pk) == null || pk in allRevoked) return@forEach
            allRevoked += pk
            newly = true
            if (pk == myPk) selfRevoked = true else if (existing.any { it.pk == pk }) remove += pk
        }
        if (selfRevoked) {
            // Removed from the account: forget every device we trusted as our own.
            remove += existing.filter { it.own_device && it.pk !in remove }.map { it.pk }
            return@runCatching RosterPlan(allRevoked, remove, emptyList(), emptyList(), true, newly)
        }
        val upsert = mutableListOf<DeviceKey.TrustedKey>()
        val added = mutableListOf<String>()
        (body["devices"] as? JsonArray).orEmpty().take(MAX_ROSTER).forEach { el ->
            val d = el as? JsonObject ?: return@forEach
            val pk = str(d, "pk") ?: return@forEach
            val raw = DeviceKey.decodePk(pk) ?: return@forEach
            if (pk == myPk || pk in allRevoked) return@forEach
            val name = (str(d, "name") ?: "Unknown device").take(64)
            fun port(k: String) = (d[k] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..65535 } ?: 0
            val old = existing.firstOrNull { it.pk == pk }
            val ip = if (pk == sender.pk) senderIp else old?.ip?.takeIf { it.isNotEmpty() } ?: str(d, "ip").orEmpty()
            if (old == null || !old.own_device) added += name
            upsert += DeviceKey.TrustedKey(
                pk = pk,
                device_id = DeviceKey.deviceIdForPk(raw),
                name = name,
                ip = ip.take(64),
                port = port("port").takeIf { it != 0 } ?: old?.port ?: 0,
                quic_port = port("quic_port").takeIf { it != 0 } ?: old?.quic_port ?: 0,
                own_device = true,
                added_at = old?.added_at ?: now,
                last_seen = old?.last_seen ?: 0
            )
        }
        RosterPlan(allRevoked, remove, upsert, added, false, newly)
    }

    /** Applies a roster from [sender] (an own device). Returns the plan that was applied. */
    @Synchronized
    fun applyRoster(context: Context, myPk: String, sender: DeviceKey.TrustedKey, senderIp: String, body: JsonObject): Result<RosterPlan> {
        val store = DeviceKey.TrustStore(context)
        return planRoster(myPk, sender, senderIp, body, revoked(context), store.load(), DeviceKey.nowSecs()).onSuccess { plan ->
            if (plan.revoked.size != revoked(context).size) saveRevoked(context, plan.revoked)
            plan.remove.forEach { store.remove(it) }
            plan.upsert.forEach { store.upsert(it) }
            if (plan.rotate) newAccountId(context)
        }
    }

    // ------------------------------------------------------------- responder

    sealed class StartResult {
        data class Ok(val body: JsonObject) : StartResult()
        object RateLimited : StartResult()
        data class Bad(val message: String) : StartResult()
    }

    private class Session(val pA: String, val pB: String, val keys: Keys, val ip: String, val created: Long)
    private class Attempt(val at: Long, val ip: String, val sid: String)

    /** Responder state: open sessions and the guess budget. Times are in milliseconds. */
    class Responder {
        private val sessions = HashMap<String, Session>()
        private val attempts = ArrayList<Attempt>()

        internal val attemptCount: Int @Synchronized get() = attempts.size

        private fun prune(now: Long) {
            sessions.values.removeAll { now - it.created >= SESSION_TTL_MS }
            attempts.removeAll { now - it.at >= RATE_WINDOW_MS }
        }

        /** Every start is one online guess, so it is counted before any work. */
        @Synchronized
        fun start(accountId: String, ip: String, pA: String, now: Long): StartResult {
            prune(now)
            if (attempts.count { it.ip == ip } >= MAX_ATTEMPTS_PER_IP ||
                attempts.size >= MAX_ATTEMPTS_TOTAL || sessions.size >= MAX_SESSIONS
            ) return StartResult.RateLimited
            val paBytes = DeviceKey.unb64(pA) ?: return StartResult.Bad("Invalid pA")
            val (pB, keys) = respond(accountId, paBytes).getOrElse { return StartResult.Bad(it.message ?: "Invalid pA") }
            val sid = DeviceKey.hex(DeviceKey.randomBytes(16))
            val pbB64 = DeviceKey.b64(pB)
            attempts += Attempt(now, ip, sid)
            sessions[sid] = Session(pA, pbB64, keys, ip, now)
            return StartResult.Ok(buildJsonObject {
                put("sid", sid)
                put("pB", pbB64)
                put("cB", DeviceKey.b64(keys.cB))
            })
        }

        /** Verifies the initiator; returns its identity and our sealed reply. */
        @Synchronized
        fun finish(id: DeviceKey.Identity, myName: String, myQuicPort: Int, ip: String, body: JsonObject, now: Long): Result<Pair<LinkedPeer, JsonObject>> = runCatching {
            prune(now)
            val sid = str(body, "sid") ?: error("Missing sid")
            // Single use: a session is consumed by its first finish, right or wrong.
            val s = sessions.remove(sid) ?: error("Unknown or expired link session")
            require(s.ip == ip) { "Link session belongs to another address" }
            val cA = DeviceKey.unb64(str(body, "cA") ?: error("Missing cA")) ?: error("Invalid cA")
            require(DeviceKey.ctEq(s.keys.cA, cA)) { "OmaID does not match" }
            val plain = open(s.keys.boxKey, "I", str(body, "box") ?: error("Missing box")).getOrThrow()
            val peer = parseIdentity(plain) { iPk -> sigTranscript(s.pA, s.pB, iPk) }
            require(peer.pk != id.publicB64) { "Cannot link a device to itself" }
            val reply = buildJsonObject {
                put("pk", id.publicB64)
                put("name", myName)
                put("device_id", id.deviceId)
                put("quic_port", myQuicPort)
                put("sig", id.signB64(sigTranscript(s.pA, s.pB, peer.pk, id.publicB64).toByteArray()))
            }
            // A correct number is not a guess: give the attempt back.
            attempts.removeAll { it.sid == sid }
            peer to buildJsonObject { put("box", seal(s.keys.boxKey, "R", reply.toString().toByteArray())) }
        }
    }
}
