package io.omarchy.omasend.account

import io.omarchy.omasend.crypto.DeviceKey
import io.omarchy.omasend.network.NetworkUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Where this device stands in its OmaID account (REMOVED is never stored: the state is cleared). */
@Serializable
enum class AccountPhase { MEMBER, WAITING, RESETTING }

@Serializable
data class AccountDeviceView(
    val pk: String,
    val name: String,
    val relay: String = "",
    val addrs: List<String> = emptyList(),
    val added: Long = 0
)

@Serializable
data class JoinView(
    val rid: String,
    val pk: String,
    val name: String,
    val code: String,
    val suspicious: Boolean = false
)

/**
 * The last account report without the secret state: what the UI shows and what survives a
 * restart while offline. Stored Keystore-encrypted (it contains the number).
 */
@Serializable
data class AccountView(
    val phase: AccountPhase,
    val number: String,
    val devices: List<AccountDeviceView> = emptyList(),
    val revoked: List<String> = emptyList(),
    val joins: List<JoinView> = emptyList(),
    val resetPending: Boolean = false,
    val myCode: String = "",
    /** Seconds until a reset completes, as of [updatedAt]. */
    val resetWait: Long = 0,
    /** Epoch seconds of the report. */
    val updatedAt: Long = 0
)

/**
 * Pure OmaID account logic (no Android, no native core), so it can be unit tested:
 * number entry, invite links, how a report maps onto the trust store, sync timing and
 * user-facing error mapping. The protocol and crypto live in the Rust core.
 */
object AccountLogic {
    const val INVITE_PREFIX = "https://omaid.app/j"
    const val NUMBER_DIGITS = 16

    // ------------------------------------------------------------------ number entry

    fun digits(input: String): String = input.filter { it in '0'..'9' }

    /** 16 digits with a valid Luhn check digit; mirrors `account_number_valid` in the core. */
    fun numberValid(input: String): Boolean {
        val d = digits(input)
        if (d.length != NUMBER_DIGITS) return false
        var sum = 0
        // Luhn over the first 15 digits, doubling from the right (the check digit's neighbour).
        for ((i, c) in d.substring(0, 15).reversed().withIndex()) {
            var v = c - '0'
            if (i % 2 == 0) {
                v *= 2
                if (v > 9) v -= 9
            }
            sum += v
        }
        return (10 - sum % 10) % 10 == d[15] - '0'
    }

    /** `1234 5678 9012 3456` (partial input is grouped too: `1234 56`). */
    fun formatNumber(input: String): String = digits(input).chunked(4).joinToString(" ")

    /** All groups, or only the last one when hidden: `•••• •••• •••• 3456`. */
    fun maskNumber(input: String, revealed: Boolean): String {
        val groups = digits(input).chunked(4)
        if (groups.isEmpty()) return ""
        return if (revealed) groups.joinToString(" ")
        else (List(groups.size - 1) { "••••" } + groups.last()).joinToString(" ")
    }

    /** Cursor mapping for the 4x4 grouped number field (digits → shown text). */
    fun groupedOffset(digitOffset: Int, length: Int): Int {
        val o = digitOffset.coerceIn(0, length)
        return if (o == 0) 0 else o + (o - 1) / 4
    }

    /** Cursor mapping for the 4x4 grouped number field (shown text → digits). */
    fun digitOffset(groupedOffset: Int, length: Int): Int =
        (groupedOffset - groupedOffset / 5).coerceIn(0, length)

    /**
     * A minimal view from the core's opaque state (phase and number only), for when no
     * report is at hand yet. Reads only the non-secret fields' presence.
     */
    fun viewFromState(state: String, now: Long): AccountView? = try {
        val o = Json.parseToJsonElement(state).jsonObject
        fun has(k: String) = !(o[k] as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()
        val phase = when {
            has("reset_sk") -> AccountPhase.RESETTING
            has("account_sk") -> AccountPhase.MEMBER
            else -> AccountPhase.WAITING
        }
        AccountView(
            phase = phase,
            number = formatNumber((o["number"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
            resetWait = if (phase == AccountPhase.RESETTING) RESET_WAIT_SECS else 0,
            updatedAt = now
        )
    } catch (_: Exception) {
        null
    }

    const val RESET_WAIT_SECS = 24 * 3600L

    // ------------------------------------------------------------------ links

    /** A QR / deep-link invite: `https://omaid.app/j#n=<digits>&k=<key>`. The fragment must be kept. */
    fun isInviteLink(text: String): Boolean {
        val t = text.trim()
        val rest = when {
            t.startsWith("$INVITE_PREFIX#") -> t.removePrefix("$INVITE_PREFIX#")
            t.startsWith("$INVITE_PREFIX/#") -> t.removePrefix("$INVITE_PREFIX/#")
            else -> return false
        }
        return rest.split('&').any { it.startsWith("n=") && digits(it.removePrefix("n=")).length == NUMBER_DIGITS }
    }

    /** What the QR scanner accepts as a link: a device pairing QR or an account invite. */
    fun isScannableLink(text: String): Boolean = DeviceKey.isPairUri(text) || isInviteLink(text)

    /** The account registry is only ever reached over HTTPS. */
    fun isAllowedUrl(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("https://")) return false
        val host = u.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
        return host.isNotEmpty() && !host.contains('@') && host.none { it.isWhitespace() }
    }

    // ------------------------------------------------------------------ reachability

    /** Our QUIC address for the account vault: only a private LAN IPv4 (no loopback / link-local). */
    fun lanAddr(ip: String, port: Int): String? {
        if (port !in 1..65535) return null
        val p = ip.trim().split('.')
        if (p.size != 4 || p.any { it.toIntOrNull() == null }) return null
        val a = p[0].toInt()
        val b = p[1].toInt()
        if (a == 127 || (a == 169 && b == 254)) return null
        if (!NetworkUtils.isPrivateOrLocalIp(ip.trim())) return null
        return "${ip.trim()}:$port"
    }

    /** "ip:port" (IPv4, or "[v6]:port") → ip and port. */
    fun parseHostPort(addr: String): Pair<String, Int>? {
        val a = addr.trim()
        val (host, port) = if (a.startsWith("[")) {
            val end = a.indexOf(']')
            if (end < 0 || a.getOrNull(end + 1) != ':') return null
            a.substring(1, end) to a.substring(end + 2)
        } else {
            if (a.count { it == ':' } != 1) return null
            a.substringBefore(':') to a.substringAfter(':')
        }
        val p = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (host.isEmpty()) return null
        return host to p
    }

    /** Extra dial hints for an account device: its LAN addresses, then its relay URL. */
    fun dialHints(d: AccountDeviceView): List<String> =
        d.addrs.filter { parseHostPort(it) != null } + listOfNotNull(d.relay.takeIf { it.startsWith("https://") })

    // ------------------------------------------------------------------ trust store

    data class TrustPlan(
        /** Revoked keys we did not know about yet. */
        val newlyRevoked: List<String>,
        /** Trusted keys to delete (revoked by the account). */
        val remove: List<String>,
        /** Own-device entries to write (only the ones that changed). */
        val upsert: List<DeviceKey.TrustedKey>,
        /** Names of devices that just became own devices. */
        val added: List<String>
    )

    /**
     * Mirrors a member report into the trust store: every account device except this one is
     * an own device (auto-accept), with its first LAN address as the IP hint; revoked keys are
     * dropped and refused from then on.
     */
    fun planTrust(
        myPk: String,
        devices: List<AccountDeviceView>,
        revoked: List<String>,
        existing: List<DeviceKey.TrustedKey>,
        knownRevoked: List<String>,
        now: Long
    ): TrustPlan {
        val newlyRevoked = revoked.filter { it != myPk && it !in knownRevoked }.distinct()
        val remove = revoked.filter { pk -> pk != myPk && existing.any { it.pk == pk } }.distinct()
        val upsert = mutableListOf<DeviceKey.TrustedKey>()
        val added = mutableListOf<String>()
        for (d in devices) {
            if (d.pk == myPk || d.pk in revoked) continue
            val raw = DeviceKey.decodePk(d.pk) ?: continue
            val old = existing.firstOrNull { it.pk == d.pk }
            val lan = d.addrs.firstNotNullOfOrNull { parseHostPort(it) }
            val key = DeviceKey.TrustedKey(
                pk = d.pk,
                device_id = DeviceKey.deviceIdForPk(raw),
                name = d.name.take(64).ifBlank { old?.name ?: "OmaSend" },
                ip = lan?.first ?: old?.ip ?: "",
                port = old?.port ?: 0,
                quic_port = lan?.second ?: old?.quic_port ?: 0,
                own_device = true,
                added_at = old?.added_at ?: now,
                last_seen = old?.last_seen ?: 0
            )
            if (old == null || !old.own_device) added += key.name
            if (key != old) upsert += key
        }
        return TrustPlan(newlyRevoked, remove, upsert, added)
    }

    // ------------------------------------------------------------------ changes and timing

    data class Changes(
        val newJoins: List<JoinView>,
        val joined: Boolean,
        val resetStarted: Boolean
    )

    /** What the user should hear about when [next] replaces [prev]. */
    fun changes(prev: AccountView?, next: AccountView): Changes {
        val seen = prev?.joins?.map { it.rid }?.toSet() ?: emptySet()
        val member = next.phase == AccountPhase.MEMBER
        return Changes(
            newJoins = if (member) next.joins.filter { it.rid !in seen } else emptyList(),
            joined = member && prev?.phase == AccountPhase.WAITING,
            resetStarted = member && next.resetPending && prev?.resetPending != true
        )
    }

    const val INVITE_SYNC_MS = 5_000L
    const val PENDING_SYNC_MS = 10_000L
    const val IDLE_SYNC_MS = 15 * 60_000L

    /**
     * How often to sync, or null for "only when asked" (app and service both stopped).
     * 5 s while an invite QR is shown, 10 s while something is pending, 15 min otherwise.
     */
    fun syncIntervalMs(view: AccountView?, inviteVisible: Boolean, active: Boolean): Long? = when {
        view == null -> null
        inviteVisible -> INVITE_SYNC_MS
        view.phase != AccountPhase.MEMBER || view.joins.isNotEmpty() -> PENDING_SYNC_MS
        active -> IDLE_SYNC_MS
        else -> null
    }

    // ------------------------------------------------------------------ errors

    /** OmaI18n key for a core error message, or null to show the message itself. */
    fun errorKey(message: String?): String? {
        val m = message?.lowercase() ?: return null
        return when {
            "unreachable" in m -> "acct_err_unreachable"
            "no account uses this number" in m -> "acct_err_no_account"
            "join request expired" in m -> "acct_err_expired"
            "same code" in m -> "acct_err_suspicious"
            "too many attempts" in m -> "acct_err_rate"
            "already belongs to an account" in m || "already taken" in m -> "acct_err_taken"
            "reset was cancelled" in m -> "acct_err_reset_cancelled"
            "reset is already in progress" in m -> "acct_err_reset_busy"
            "request is gone" in m -> "acct_err_gone"
            "changed while saving" in m || "changed during the reset" in m -> "acct_err_conflict"
            "no longer exists" in m -> "acct_err_no_account"
            else -> null
        }
    }

    /** Errors after which the local waiting / resetting state is useless and is dropped. */
    fun isTerminal(phase: AccountPhase?, message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return when (phase) {
            AccountPhase.WAITING -> "join request expired" in m
            AccountPhase.RESETTING -> "reset was cancelled" in m || "no longer exists" in m
            else -> false
        }
    }

    /** Hours and minutes left, rounded up to the minute. */
    fun hoursMinutes(seconds: Long): Pair<Long, Long> {
        val mins = (seconds.coerceAtLeast(0) + 59) / 60
        return mins / 60 to mins % 60
    }
}
