package io.omarchy.omasend.crypto

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable

/**
 * The legacy OmaID. It is PUBLIC: older versions broadcast it in LAN beacons, ping responses and
 * request headers, and it has only ~50 bits of entropy. Nothing may use it as a secret: the
 * rendezvous topic, the HKDF "E2EE" key and the p2p session key derived from it below protect
 * nothing and are kept only so old data and test vectors still parse. Authentication comes from
 * the Ed25519 device key (DeviceKey) and the QUIC core; the secret account number lives in
 * OmaLink and never goes on the wire.
 */
@Serializable
data class OmaIdentity(
    val formattedId: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    val rawId: String
        get() = unformat(formattedId)

    @Suppress("DEPRECATION")
    val blindTopic: String
        get() = deriveBlindTopic(formattedId)

    @Suppress("DEPRECATION")
    companion object {
        private const val PREFS_NAME = "omasend_identity_store"
        private const val KEY_OMA_ID = "active_oma_id"
        private const val KEY_CREATED_AT = "oma_id_created_at"
        private val RENDEZVOUS_SALT = "omasend-e2ee-rendezvous-salt-v1".toByteArray(Charsets.UTF_8)
        private val RENDEZVOUS_INFO = "omasend-e2ee-rendezvous-aes256-gcm-key-v1".toByteArray(Charsets.UTF_8)
        private val RENDEZVOUS_TOPIC_KEY = "omasend-gh-rendezvous-v1".toByteArray(Charsets.UTF_8)

        /**
         * Standard RFC 2104 HMAC-SHA256 calculation.
         */
        fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(key, "HmacSHA256")
            mac.init(secretKey)
            return mac.doFinal(data)
        }

        /**
         * Standard RFC 5869 HKDF-SHA256 extraction and expansion for 32-byte key derivation.
         */
        fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
            val prk = hmacSha256(salt, ikm)
            val infoWithCounter = ByteArray(info.size + 1)
            System.arraycopy(info, 0, infoWithCounter, 0, info.size)
            infoWithCounter[info.size] = 0x01.toByte()
            return hmacSha256(prk, infoWithCounter)
        }

        /**
         * Validates a 16-digit OmaID using Luhn mod 10 checksum.
         * Case-insensitive and supports formatted or unformatted inputs.
         */
        fun isValid(input: String?): Boolean {
            if (input.isNullOrBlank()) return false
            val clean = unformat(input)
            if (clean.length != 16) return false
            if (!clean.all { it.isDigit() }) return false

            var sum = 0
            for (i in 0 until 16) {
                val digit = clean[i].digitToInt()
                if (i % 2 == 0) {
                    val doubled = digit * 2
                    sum += if (doubled > 9) doubled - 9 else doubled
                } else {
                    sum += digit
                }
            }
            return sum % 10 == 0
        }

        /**
         * Parses and formats any case-insensitive or unformatted OmaID input.
         * Returns OmaIdentity instance if valid, null otherwise.
         */
        fun parse(input: String?): OmaIdentity? {
            if (!isValid(input)) return null
            return OmaIdentity(format(input!!))
        }

        /**
         * Generates a random 16-digit OmaID satisfying Luhn mod 10.
         */
        fun generateRandomOmaId(): String {
            val random = SecureRandom()
            val digits = IntArray(16)
            var sum = 0
            for (i in 0 until 15) {
                digits[i] = random.nextInt(10)
                if (i % 2 == 0) {
                    val doubled = digits[i] * 2
                    sum += if (doubled > 9) doubled - 9 else doubled
                } else {
                    sum += digits[i]
                }
            }
            val checkDigit = (10 - (sum % 10)) % 10
            digits[15] = checkDigit
            val raw = digits.joinToString("")
            return format(raw)
        }

        fun generate(): String = generateRandomOmaId()

        /**
         * Formats raw digits into XXXX-XXXX-XXXX-XXXX format.
         */
        fun format(raw: String): String {
            val clean = unformat(raw)
            if (clean.isEmpty()) return ""
            return clean.chunked(4).joinToString("-")
        }

        /**
         * Removes spaces and hyphens from OmaID and normalizes to uppercase.
         */
        fun unformat(formatted: String): String {
            return formatted.replace("-", "").replace(" ", "").trim().uppercase()
        }

        /**
         * Computes the 64-character hex topic ID for blinded rendezvous using HMAC-SHA256.
         * 100% byte-compatible with Rust rendezvous.rs compute_rendezvous_topic.
         */
        @Deprecated("Insecure: keyed only by the public OmaID. Must not protect confidentiality or authenticity.")
        fun deriveBlindTopic(omaId: String, customTopicKey: ByteArray = RENDEZVOUS_TOPIC_KEY): String {
            val raw = unformat(omaId)
            val ikm = raw.toByteArray(Charsets.UTF_8)
            val topicBytes = hmacSha256(ikm, customTopicKey)
            return topicBytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Derives a 256-bit symmetric key from OmaID using RFC 5869 HKDF-SHA256.
         * 100% byte-compatible with Rust rendezvous.rs derive_rendezvous_key.
         */
        @Deprecated("Insecure: keyed only by the public OmaID. Must not protect confidentiality or authenticity.")
        fun deriveRendezvousKey(omaId: String): ByteArray {
            val raw = unformat(omaId)
            val ikm = raw.toByteArray(Charsets.UTF_8)
            return hkdfSha256(ikm, RENDEZVOUS_SALT, RENDEZVOUS_INFO)
        }

        /**
         * Derives a 256-bit AES key from OmaID.
         */
        @Deprecated("Insecure: keyed only by the public OmaID. Must not protect confidentiality or authenticity.")
        fun deriveAesKey(omaId: String): SecretKeySpec {
            return SecretKeySpec(deriveRendezvousKey(omaId), "AES")
        }

        /**
         * Encrypts binary payload using AES-256-GCM.
         * Output layout: [12-byte IV] + [Ciphertext with 128-bit Auth Tag]
         */
        @Deprecated("Insecure: keyed only by the public OmaID. Must not protect confidentiality or authenticity.")
        fun encryptPayload(plaintext: ByteArray, omaId: String, aad: ByteArray? = null): ByteArray {
            val key = deriveAesKey(omaId)
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            if (aad != null) {
                cipher.updateAAD(aad)
            }
            val ciphertext = cipher.doFinal(plaintext)
            val output = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, output, 0, iv.size)
            System.arraycopy(ciphertext, 0, output, iv.size, ciphertext.size)
            return output
        }

        /**
         * Decrypts binary payload using AES-256-GCM.
         */
        @Deprecated("Insecure: keyed only by the public OmaID. Must not protect confidentiality or authenticity.")
        fun decryptPayload(ciphertextWithIv: ByteArray, omaId: String, aad: ByteArray? = null): ByteArray {
            if (ciphertextWithIv.size < 28) {
                throw IllegalArgumentException("Ciphertext payload too short for AES-GCM")
            }
            val key = deriveAesKey(omaId)
            val iv = ciphertextWithIv.copyOfRange(0, 12)
            val ciphertext = ciphertextWithIv.copyOfRange(12, ciphertextWithIv.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            if (aad != null) {
                cipher.updateAAD(aad)
            }
            return cipher.doFinal(ciphertext)
        }

        /**
         * String helper: Encrypts plaintext string to Base64 AES-256-GCM.
         */
        @Deprecated("Insecure: keyed only by the public OmaID. Must not protect confidentiality or authenticity.")
        fun encryptString(plaintext: String, omaId: String): String {
            val encrypted = encryptPayload(plaintext.toByteArray(Charsets.UTF_8), omaId)
            return try {
                android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
            } catch (_: Throwable) {
                Base64.getEncoder().encodeToString(encrypted)
            }
        }

        /**
         * Derives a pairwise session key between two OmaIDs using symmetric HKDF
         * 100% byte-compatible with Linux rendezvous.rs derive_p2p_session_key
         */
        @Deprecated("Insecure: keyed only by the public OmaID. Must not protect confidentiality or authenticity.")
        fun deriveP2pSessionKey(localOmaId: String, remoteOmaId: String): SecretKeySpec {
            val normLocal = unformat(localOmaId)
            val normRemote = unformat(remoteOmaId)
            val (first, second) = if (normLocal <= normRemote) Pair(normLocal, normRemote) else Pair(normRemote, normLocal)
            val ikm = (first + second).toByteArray(Charsets.UTF_8)
            val rawKey = hkdfSha256(
                ikm,
                "omasend-p2p-noise-salt-v2".toByteArray(Charsets.UTF_8),
                "omasend-p2p-chacha-aes-tunnel-v2".toByteArray(Charsets.UTF_8)
            )
            return SecretKeySpec(rawKey, "AES")
        }

        /**
         * Encrypts plaintext bytes using pairwise P2P AES-256-GCM
         */
        @Deprecated("Insecure: keyed only by the public OmaID. Must not protect confidentiality or authenticity.")
        fun encryptP2pPayload(plaintext: ByteArray, localOmaId: String, remoteOmaId: String): ByteArray {
            val key = deriveP2pSessionKey(localOmaId, remoteOmaId)
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(plaintext)
            val output = ByteArray(12 + ciphertext.size)
            System.arraycopy(iv, 0, output, 0, 12)
            System.arraycopy(ciphertext, 0, output, 12, ciphertext.size)
            return output
        }

        /**
         * Decrypts ciphertext bytes using pairwise P2P AES-256-GCM
         */
        @Deprecated("Insecure: keyed only by the public OmaID. Must not protect confidentiality or authenticity.")
        fun decryptP2pPayload(ciphertextWithIv: ByteArray, localOmaId: String, remoteOmaId: String): ByteArray {
            if (ciphertextWithIv.size < 28) {
                throw IllegalArgumentException("P2P ciphertext payload too short")
            }
            val key = deriveP2pSessionKey(localOmaId, remoteOmaId)
            val iv = ciphertextWithIv.copyOfRange(0, 12)
            val ciphertext = ciphertextWithIv.copyOfRange(12, ciphertextWithIv.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(ciphertext)
        }

        /**
         * String helper: Decrypts Base64 AES-256-GCM ciphertext to plaintext string.
         */
        @Deprecated("Insecure: keyed only by the public OmaID. Must not protect confidentiality or authenticity.")
        fun decryptString(encryptedBase64: String, omaId: String): String {
            val bytes = try {
                android.util.Base64.decode(encryptedBase64.trim(), android.util.Base64.DEFAULT)
            } catch (_: Throwable) {
                Base64.getDecoder().decode(encryptedBase64.trim())
            }
            val decrypted = decryptPayload(bytes, omaId)
            return String(decrypted, Charsets.UTF_8)
        }

        /**
         * Gets stored identity or generates a new one.
         */
        fun getOrGenerate(context: Context): OmaIdentity {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val storedId = prefs.getString(KEY_OMA_ID, null)
            val createdAt = prefs.getLong(KEY_CREATED_AT, 0L)

            if (storedId != null && isValid(storedId)) {
                return OmaIdentity(format(storedId), if (createdAt > 0L) createdAt else System.currentTimeMillis())
            }

            val newId = generateRandomOmaId()
            val now = System.currentTimeMillis()
            prefs.edit()
                .putString(KEY_OMA_ID, unformat(newId))
                .putLong(KEY_CREATED_AT, now)
                .apply()
            return OmaIdentity(newId, now)
        }

        /**
         * Saves a custom OmaID if valid.
         */
        fun save(context: Context, omaId: String): OmaIdentity {
            if (!isValid(omaId)) {
                throw IllegalArgumentException("Geçersiz 16 haneli OmaID formatı veya Luhn sağlama hatası")
            }
            val formatted = format(omaId)
            val now = System.currentTimeMillis()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_OMA_ID, unformat(formatted))
                .putLong(KEY_CREATED_AT, now)
                .apply()
            return OmaIdentity(formatted, now)
        }

        /**
         * Resets and generates a fresh OmaID.
         */
        fun reset(context: Context): OmaIdentity {
            val newId = generateRandomOmaId()
            val now = System.currentTimeMillis()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_OMA_ID, unformat(newId))
                .putLong(KEY_CREATED_AT, now)
                .apply()
            return OmaIdentity(newId, now)
        }
    }
}
