package io.omarchy.omasend.account

import android.content.Context
import io.omarchy.omasend.crypto.DeviceKey
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Keystore-encrypted storage for the OmaID account, like OmaLink's `account_id.enc`:
 * the secret core state and the last report (it contains the number). There is no
 * plaintext fallback: if the Android Keystore cannot wrap or unwrap, the account feature
 * reports an error instead. Both files are excluded from backup (backup_rules.xml).
 */
class AccountStore(context: Context) {
    class KeystoreUnavailableException : IllegalStateException("Android Keystore is unavailable")
    class LockedException(val file: String) : IllegalStateException("$file could not be decrypted")

    private val dir = context.applicationContext.filesDir
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun hasState(): Boolean = File(dir, STATE_FILE).exists()

    /** Throws [KeystoreUnavailableException] if secrets cannot be stored safely right now. */
    fun ensureUsable() {
        val probe = DeviceKey.randomBytes(16)
        val wrapped = DeviceKey.wrap(probe) ?: throw KeystoreUnavailableException()
        val back = DeviceKey.unwrapSecret(wrapped) ?: throw KeystoreUnavailableException()
        if (!DeviceKey.ctEq(probe, back)) throw KeystoreUnavailableException()
    }

    /** The secret core state, null when there is no account. Throws [LockedException] if it cannot be read. */
    @Synchronized
    fun loadState(): String? = read(STATE_FILE)?.takeIf { it.isNotBlank() }

    @Synchronized
    fun saveState(state: String) = write(STATE_FILE, state)

    @Synchronized
    fun loadView(): AccountView? = read(VIEW_FILE)?.let {
        try {
            json.decodeFromString(AccountView.serializer(), it)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun saveView(view: AccountView) = write(VIEW_FILE, json.encodeToString(AccountView.serializer(), view))

    /** Forgets the account on this device. */
    @Synchronized
    fun clear() {
        for (name in listOf(STATE_FILE, VIEW_FILE)) {
            val f = File(dir, name)
            if (f.exists()) {
                // Best effort overwrite; the content is already Keystore-encrypted.
                try { f.writeBytes(ByteArray(f.length().toInt().coerceIn(0, 65536))) } catch (_: Exception) {}
                f.delete()
            }
            File(dir, "$name.tmp").delete()
        }
    }

    private fun read(name: String): String? {
        val f = File(dir, name)
        if (!f.exists()) return null
        val blob = f.readBytes()
        val plain = if (blob.size > 12) DeviceKey.unwrapSecret(blob) else null
        return plain?.toString(Charsets.UTF_8) ?: throw LockedException(name)
    }

    private fun write(name: String, text: String) {
        val wrapped = DeviceKey.wrap(text.toByteArray(Charsets.UTF_8)) ?: throw KeystoreUnavailableException()
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(wrapped)
        if (!tmp.renameTo(target)) {
            target.writeBytes(wrapped)
            tmp.delete()
        }
    }

    companion object {
        const val STATE_FILE = "omaid_account.enc"
        const val VIEW_FILE = "omaid_report.enc"
    }
}
