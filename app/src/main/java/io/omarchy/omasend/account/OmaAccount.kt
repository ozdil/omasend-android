package io.omarchy.omasend.account

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.omarchy.omasend.MainActivity
import io.omarchy.omasend.OmaSendApp
import io.omarchy.omasend.R
import io.omarchy.omasend.crypto.DeviceKey
import io.omarchy.omasend.crypto.OmaLink
import io.omarchy.omasend.network.CoreNode
import io.omarchy.omasend.network.NetworkUtils
import io.omarchy.omasend.ui.OmaI18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.omasend_core.AccountDevice
import uniffi.omasend_core.AccountReport
import uniffi.omasend_core.AccountStatus
import uniffi.omasend_core.CoreException
import uniffi.omasend_core.accountApprove
import uniffi.omasend_core.accountCancelReset
import uniffi.omasend_core.accountChangeNumber
import uniffi.omasend_core.accountCreate
import uniffi.omasend_core.accountInvite
import uniffi.omasend_core.accountJoin
import uniffi.omasend_core.accountLeave
import uniffi.omasend_core.accountReject
import uniffi.omasend_core.accountRemove
import uniffi.omasend_core.accountResetStart
import uniffi.omasend_core.accountSync
import java.util.concurrent.ConcurrentHashMap

/**
 * The OmaID account on this phone ("Mullvad security, Quick Share simplicity"): one
 * anonymous 16-digit number for all of a person's devices. The Rust core does the crypto
 * and the registry protocol; this class provides HTTPS, Keystore storage, scheduling,
 * notifications, and mirrors the account's devices into the trust store as own devices.
 *
 * Every core call blocks (Argon2id, 64 MiB) and runs on Dispatchers.IO, one at a time.
 */
class OmaAccount(private val app: OmaSendApp) {

    data class Invite(val uri: String, val expiresAtMs: Long)

    data class UiState(
        /** False until the stored account was read. */
        val loaded: Boolean = false,
        /** null = no account on this device. */
        val view: AccountView? = null,
        /** Secrets cannot be stored or read (Keystore): the feature is unavailable. */
        val storageError: String? = null,
        val busy: Boolean = false,
        /** Result of the last user action. */
        val error: String? = null,
        /** Last background sync failure (cleared by the next success). */
        val syncError: String? = null,
        val invite: Invite? = null
    )

    private val store = AccountStore(app)
    private val http by lazy { AccountHttpClient() }
    private val mutex = Mutex()
    private val hints = ConcurrentHashMap<String, List<String>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _openRequested = MutableStateFlow(false)
    /** Set by notifications and deep links: the UI shows the account screen and consumes it. */
    val openRequested: StateFlow<Boolean> = _openRequested.asStateFlow()

    private val _pendingInvite = MutableStateFlow<String?>(null)
    /** An invite link from outside the join screen (deep link, home scanner) awaiting confirmation. */
    val pendingInvite: StateFlow<String?> = _pendingInvite.asStateFlow()

    /** Set by the foreground service while it runs. */
    @Volatile var serviceRunning = false
    @Volatile private var inviteVisible = false
    @Volatile private var lastSyncAt = 0L
    @Volatile private var lastWakeSyncAt = 0L

    fun start() {
        app.appScope.launch(Dispatchers.IO) {
            loadStored()
            schedulerLoop()
        }
    }

    /** True when this device has account state (member, waiting or resetting). Cheap; any thread. */
    fun hasAccount(): Boolean = store.hasState()

    /** Extra dial hints (LAN addresses, relay URL) for an account device. */
    fun dialHints(pk: String): List<String> = hints[pk].orEmpty()

    // ------------------------------------------------------------------ UI requests

    fun requestOpen() { _openRequested.value = true }
    fun consumeOpen() { _openRequested.value = false }

    /** An invite link arrived (deep link or scanner): ask the user before joining. */
    fun offerInvite(link: String) {
        if (!AccountLogic.isInviteLink(link)) return
        _pendingInvite.value = link.trim()
        requestOpen()
    }

    fun clearPendingInvite() { _pendingInvite.value = null }
    fun clearError() = _state.update { it.copy(error = null) }

    // ------------------------------------------------------------------ scheduling

    /** App came to the foreground. */
    fun onForeground() = requestSync()

    /** Screen turned on: one sync (periodic syncs are skipped while it is off). */
    fun onScreenOn() = requestSync()

    fun requestSync() {
        wake.trySend(Unit)
    }

    private fun requestSyncLater(ms: Long) {
        app.appScope.launch {
            delay(ms)
            requestSync()
        }
    }

    private fun interactive(): Boolean =
        (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true

    /**
     * Syncs when woken (foreground, service start, screen on, invite shown) and periodically
     * (see AccountLogic.syncIntervalMs) while the screen is on. No wake locks, no alarms.
     */
    private suspend fun schedulerLoop() {
        while (currentCoroutineContext().isActive) {
            val usable = store.hasState() && _state.value.storageError == null
            val interval = if (!usable) null else AccountLogic.syncIntervalMs(
                _state.value.view,
                inviteVisible,
                serviceRunning || app.isInForeground
            )
            // Screen off: no periodic syncs, only wake-ups (screen on, foreground).
            val wait = if (interval == null || !interactive()) MAX_IDLE_WAIT_MS
            else (lastSyncAt + interval - SystemClock.elapsedRealtime()).coerceIn(MIN_LOOP_MS, MAX_IDLE_WAIT_MS)
            val woken = withTimeoutOrNull(wait) { wake.receive(); true } ?: false
            if (!store.hasState() || _state.value.storageError != null) continue
            val now = SystemClock.elapsedRealtime()
            val due = interval != null && now - lastSyncAt >= interval && interactive()
            // Wake-ups (onResume, screen on) are coalesced so quick toggling costs one request.
            val wakeOk = woken && now - lastWakeSyncAt >= MIN_WAKE_GAP_MS
            if (due || wakeOk) {
                if (woken) lastWakeSyncAt = now
                syncQuietly()
            }
        }
    }

    private suspend fun syncQuietly() {
        lastSyncAt = SystemClock.elapsedRealtime()
        try {
            mutex.withLock { syncLocked() }
            _state.update { it.copy(syncError = null) }
        } catch (t: Throwable) {
            Log.i(TAG, "account sync failed: ${rawMessage(t)}")
            _state.update { it.copy(syncError = friendly(t)) }
        }
    }

    // ------------------------------------------------------------------ operations

    /**
     * Runs a user action on IO, one at a time; failures land in [UiState.error]. It runs in the
     * app scope, so leaving the screen does not abandon a half-applied result. [membership]:
     * the action can add or drop the account, so the core node may need relays on or off.
     */
    private suspend fun op(membership: Boolean = false, block: suspend () -> Unit): Boolean = app.appScope.async(Dispatchers.IO) {
        _state.update { it.copy(busy = true, error = null) }
        try {
            mutex.withLock { block() }
            if (membership) afterMembershipChange()
            true
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.i(TAG, "account action failed: ${rawMessage(t)}")
            _state.update { it.copy(error = friendly(t)) }
            false
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }.await()

    suspend fun create(): Boolean = op(membership = true) {
        requireNoAccount()
        store.ensureUsable()
        val seed = DeviceKey.seed(app)
        val me = me()
        val st = accountCreate(http, SERVER, "", me, seed)
        store.saveState(st)
        syncOrFallback(st)
    }

    /** [input] is a typed number or an invite link (`https://omaid.app/j#n=…&k=…`). */
    suspend fun join(input: String): Boolean = op(membership = true) {
        requireNoAccount()
        val link = AccountLogic.isInviteLink(input)
        if (!link && !AccountLogic.numberValid(input)) error(OmaI18n.t("acct_number_invalid"))
        store.ensureUsable()
        val number = if (link) input.trim() else AccountLogic.digits(input)
        applyReport(accountJoin(http, SERVER, number, me(), DeviceKey.seed(app)))
    }

    /** With no device left to approve: claims the account with the number after a 24 h wait. */
    suspend fun startReset(number: String): Boolean = op(membership = true) {
        requireNoAccount()
        if (!AccountLogic.numberValid(number)) error(OmaI18n.t("acct_number_invalid"))
        store.ensureUsable()
        val st = accountResetStart(http, SERVER, AccountLogic.digits(number))
        store.saveState(st)
        syncOrFallback(st)
    }

    /** Manual refresh (pull to refresh, retry). */
    suspend fun sync(): Boolean = op { syncLocked() }

    suspend fun approve(rid: String): Boolean = op {
        val st = accountApprove(http, requireState(), rid, DeviceKey.seed(app))
        store.saveState(st)
        syncLocked()
    }

    suspend fun reject(rid: String): Boolean = op {
        accountReject(http, requireState(), rid)
        syncLocked()
    }

    /** Removes another device from the account (keys are rotated). */
    suspend fun remove(pk: String): Boolean = op {
        val st = accountRemove(http, requireState(), pk)
        store.saveState(st)
        // The account no longer lists it; drop it here at once (the report revokes it too).
        OmaLink.markRevoked(app, listOf(pk))
        hints.remove(pk)
        syncLocked()
    }

    suspend fun changeNumber(): Boolean = op {
        val st = accountChangeNumber(http, requireState())
        store.saveState(st)
        syncLocked()
    }

    suspend fun cancelReset(): Boolean = op {
        accountCancelReset(http, requireState())
        syncLocked()
    }

    /** Leaves the account everywhere and forgets it here. */
    suspend fun leave(): Boolean = op(membership = true) {
        accountLeave(http, requireState(), DeviceKey.seed(app))
        forget(notify = false)
    }

    /**
     * Drops a join request or reset that is still waiting, on this device only. The
     * registry lets an unanswered join request expire by itself.
     */
    suspend fun abandon(): Boolean = op(membership = true) {
        val phase = _state.value.view?.phase
        if (phase == AccountPhase.MEMBER) error("Use leave for a member device")
        store.clear()
        hints.clear()
        _state.update { it.copy(view = null, invite = null, syncError = null) }
    }

    /** Creates a QR invite (10 min); syncs every 5 s while it is shown so the scanner is approved. */
    suspend fun showInvite(): Boolean = op {
        val inv = accountInvite(requireState())
        store.saveState(inv.state)
        inviteVisible = true
        _state.update { it.copy(invite = Invite(inv.uri, System.currentTimeMillis() + INVITE_TTL_MS)) }
        requestSync()
    }

    fun hideInvite() {
        inviteVisible = false
        _state.update { it.copy(invite = null) }
        requestSync()
    }

    // ------------------------------------------------------------------ internals

    /**
     * After a state-creating call the account exists even if the follow-up sync fails (no
     * network): show what the state says and let the scheduler fill in the rest.
     */
    private fun syncOrFallback(st: String) {
        try {
            applyReport(accountSync(http, st, me(), DeviceKey.seed(app)))
        } catch (t: Throwable) {
            Log.i(TAG, "first account sync failed: ${rawMessage(t)}")
            AccountLogic.viewFromState(st, DeviceKey.nowSecs())?.let { v ->
                store.saveView(v)
                _state.update { it.copy(view = v, syncError = friendly(t)) }
            }
            requestSyncLater(10_000L)
        }
    }

    private fun loadStored() {
        try {
            val state = if (store.hasState()) store.loadState() else null
            // The report file may be missing (crash between writes): start from the state.
            val view = state?.let { store.loadView() ?: AccountLogic.viewFromState(it, 0) }
            view?.let { setHints(it) }
            _state.update { it.copy(loaded = true, view = view) }
        } catch (t: Throwable) {
            Log.w(TAG, "stored OmaID account unreadable: ${t.message}")
            _state.update { it.copy(loaded = true, storageError = OmaI18n.t("acct_err_storage_locked")) }
        }
    }

    private fun requireNoAccount() {
        if (_state.value.storageError != null) error(_state.value.storageError ?: "")
        if (store.hasState()) error(OmaI18n.t("acct_already_member"))
    }

    private fun requireState(): String = store.loadState() ?: error(OmaI18n.t("acct_settings_none"))

    /** Must hold [mutex]. */
    private fun syncLocked() {
        val st = store.loadState() ?: return
        val phase = _state.value.view?.phase
        try {
            applyReport(accountSync(http, st, me(), DeviceKey.seed(app)))
        } catch (e: CoreException) {
            if (AccountLogic.isTerminal(phase, rawMessage(e))) {
                store.clear()
                hints.clear()
                _state.update { it.copy(view = null) }
                restartNodeIfNeeded()
            }
            throw e
        }
    }

    private fun afterMembershipChange() {
        restartNodeIfNeeded()
        // Once the restarted node reaches its relay, the next sync publishes the relay URL.
        requestSyncLater(15_000L)
    }

    /** Relays (internet path) are used only with an account: restart the node if that changed. */
    private fun restartNodeIfNeeded() {
        app.appScope.launch(Dispatchers.IO) { CoreNode.ensureWanMode(app) }
    }

    /** This device as the account sees it. */
    private fun me(): AccountDevice {
        val id = DeviceKey.identity(app)
        val node = CoreNode.node
        val port = runCatching { node?.boundPort()?.toInt() }.getOrNull()?.takeIf { it in 1..65535 } ?: CoreNode.QUIC_PORT
        val relay = runCatching { node?.relayUrl() }.getOrNull().orEmpty().takeIf { it.startsWith("https://") } ?: ""
        val addrs = listOfNotNull(AccountLogic.lanAddr(NetworkUtils.getLocalIpAddress(), port))
        return AccountDevice(id.publicB64, NetworkUtils.getDeviceName(app).take(64), relay, addrs, 0uL)
    }

    private fun AccountReport.toView(): AccountView = AccountView(
        phase = when (status) {
            AccountStatus.WAITING -> AccountPhase.WAITING
            AccountStatus.RESETTING -> AccountPhase.RESETTING
            else -> AccountPhase.MEMBER
        },
        number = number,
        devices = devices.map { AccountDeviceView(it.pk, it.name, it.relay, it.addrs, it.added.toLong()) },
        revoked = revoked,
        joins = joins.map { JoinView(it.rid, it.pk, it.name, it.code, it.suspicious) },
        resetPending = resetPending,
        myCode = myCode,
        resetWait = resetWait.toLong(),
        updatedAt = DeviceKey.nowSecs()
    )

    /** Persists a report and applies it to the trust store, dial hints and notifications. */
    private fun applyReport(r: AccountReport) {
        if (r.status == AccountStatus.REMOVED) {
            forget(notify = true)
            restartNodeIfNeeded()
            return
        }
        val prev = _state.value.view
        if (r.state.isNotBlank()) store.saveState(r.state)
        val next = r.toView()
        store.saveView(next)
        val myPk = DeviceKey.identity(app).publicB64
        var added: List<String> = emptyList()
        if (next.phase == AccountPhase.MEMBER) {
            val trust = DeviceKey.TrustStore(app)
            val plan = AccountLogic.planTrust(myPk, next.devices, next.revoked, trust.load(), OmaLink.revoked(app), DeviceKey.nowSecs())
            if (plan.newlyRevoked.isNotEmpty() || plan.remove.isNotEmpty()) {
                OmaLink.markRevoked(app, (plan.newlyRevoked + plan.remove).distinct())
            }
            plan.upsert.forEach { trust.upsert(it) }
            added = plan.added
        }
        setHints(next)
        _state.update { it.copy(view = next) }

        val ch = AccountLogic.changes(prev, next)
        ch.newJoins.forEach { notifyJoin(it) }
        // Requests answered here or on another device: their notifications are stale.
        prev?.joins?.filter { old -> next.joins.none { it.rid == old.rid } }?.forEach {
            NotificationManagerCompat.from(app).cancel(joinNotificationId(it.rid))
        }
        when {
            ch.joined -> notifySimple(ID_ADDED, "acct_notif_added_title", OmaI18n.t("acct_notif_joined"), silent = true)
            prev?.phase == AccountPhase.MEMBER && added.isNotEmpty() ->
                notifySimple(ID_ADDED, "acct_notif_added_title", OmaI18n.f("acct_notif_added", added.joinToString(", ")), silent = true)
        }
        if (ch.resetStarted) notifySimple(ID_RESET, "acct_notif_reset_title", OmaI18n.t("acct_notif_reset"), silent = false)
        if (ch.joined) {
            // A new member: let the other devices learn our relay and addresses soon.
            requestSyncLater(5_000L)
        }
    }

    /** Out of the account: forget the devices it gave us and the state. */
    private fun forget(notify: Boolean) {
        val prev = _state.value.view
        val myPk = DeviceKey.identityOrNull(app)?.publicB64
        val trust = DeviceKey.TrustStore(app)
        prev?.devices?.filter { it.pk != myPk }?.forEach { trust.remove(it.pk) }
        store.clear()
        hints.clear()
        inviteVisible = false
        _state.update { it.copy(view = null, invite = null, syncError = null) }
        if (notify && prev?.phase == AccountPhase.MEMBER) {
            notifySimple(ID_REMOVED, "acct_notif_removed_title", OmaI18n.t("acct_notif_removed"), silent = false)
        }
    }

    private fun setHints(view: AccountView) {
        hints.clear()
        view.devices.forEach { d -> AccountLogic.dialHints(d).takeIf { it.isNotEmpty() }?.let { hints[d.pk] = it } }
    }

    private fun rawMessage(t: Throwable): String =
        (t as? CoreException.Failed)?.msg ?: t.message ?: t.javaClass.simpleName

    private fun friendly(t: Throwable): String = when (t) {
        is AccountStore.KeystoreUnavailableException -> OmaI18n.t("acct_err_keystore")
        is AccountStore.LockedException -> OmaI18n.t("acct_err_storage_locked")
        is DeviceKey.DeviceKeyUnavailableException -> OmaI18n.t("acct_err_device_key")
        else -> {
            val raw = rawMessage(t)
            AccountLogic.errorKey(raw)?.let { OmaI18n.t(it) } ?: raw
        }
    }

    // ------------------------------------------------------------------ notifications

    private fun openIntent(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        app, requestCode,
        Intent(app, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_ACCOUNT, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun notifyJoin(j: JoinView) {
        val text = OmaI18n.f("acct_notif_join", j.name.ifBlank { "?" }, j.code)
        post(
            joinNotificationId(j.rid),
            NotificationCompat.Builder(app, OmaSendApp.CHANNEL_ACCOUNT)
                .setSmallIcon(R.drawable.ic_stat_omasend)
                .setContentTitle(OmaI18n.t("acct_notif_join_title"))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(openIntent(REQ_OPEN))
                .setAutoCancel(true)
        )
    }

    private fun joinNotificationId(rid: String) = ID_JOIN_BASE + (rid.hashCode() and 0xFFF)

    private fun notifySimple(id: Int, titleKey: String, text: String, silent: Boolean) {
        post(
            id,
            NotificationCompat.Builder(app, OmaSendApp.CHANNEL_ACCOUNT)
                .setSmallIcon(R.drawable.ic_stat_omasend)
                .setContentTitle(OmaI18n.t(titleKey))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setSilent(silent)
                .setContentIntent(openIntent(REQ_OPEN))
                .setAutoCancel(true)
        )
    }

    private fun post(id: Int, b: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(app).notify(id, b.build())
        } catch (_: SecurityException) {
            // Notification permission not granted; the account screen still shows it.
        }
    }

    companion object {
        private const val TAG = "OmaAccount"
        const val SERVER = "https://id.omaid.app"
        const val EXTRA_OPEN_ACCOUNT = "io.omarchy.omasend.OPEN_ACCOUNT"
        const val INVITE_TTL_MS = 10 * 60_000L
        private const val MAX_IDLE_WAIT_MS = 60_000L
        private const val MIN_WAKE_GAP_MS = 5_000L
        private const val MIN_LOOP_MS = 1_000L
        private const val REQ_OPEN = 0x0A1D
        private const val ID_ADDED = 0x0A1D01
        private const val ID_REMOVED = 0x0A1D02
        private const val ID_RESET = 0x0A1D03
        private const val ID_JOIN_BASE = 0x0A2000
    }
}
