package io.omarchy.omasend

import io.omarchy.omasend.account.AccountDeviceView
import io.omarchy.omasend.account.AccountLogic
import io.omarchy.omasend.account.AccountPhase
import io.omarchy.omasend.account.AccountView
import io.omarchy.omasend.account.JoinView
import io.omarchy.omasend.crypto.DeviceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure OmaID account logic (account/AccountLogic.kt); the protocol itself is tested in the Rust core. */
class AccountLogicTest {
    private fun pk(b: Int) = DeviceKey.b64(ByteArray(32) { b.toByte() })
    private val me = pk(1)
    private val laptop = pk(2)
    private val tablet = pk(3)

    @Test
    fun numberLuhnMatchesCore() {
        // Same vectors as core/src/account/tests.rs.
        assertTrue(AccountLogic.numberValid("4539 1488 0343 6467"))
        assertTrue(AccountLogic.numberValid("4539148803436467"))
        assertFalse(AccountLogic.numberValid("4539 1488 0343 6468"))
        assertFalse(AccountLogic.numberValid("1234"))
        assertTrue(AccountLogic.numberValid("1234-5678-9012-3452"))
        assertFalse(AccountLogic.numberValid("45391488034364670"))
    }

    @Test
    fun formatsAndMasksTheNumber() {
        assertEquals("4539 1488 0343 6467", AccountLogic.formatNumber("4539148803436467"))
        assertEquals("4539 14", AccountLogic.formatNumber("453914"))
        assertEquals("•••• •••• •••• 6467", AccountLogic.maskNumber("4539 1488 0343 6467", revealed = false))
        assertEquals("4539 1488 0343 6467", AccountLogic.maskNumber("4539148803436467", revealed = true))
        assertEquals("", AccountLogic.maskNumber("", revealed = false))
    }

    @Test
    fun groupedFieldCursorMappingIsConsistent() {
        for (len in 0..16) {
            val shown = AccountLogic.formatNumber("1".repeat(len)).length
            assertEquals(shown, AccountLogic.groupedOffset(len, len))
            for (o in 0..len) {
                val t = AccountLogic.groupedOffset(o, len)
                assertTrue(t in 0..shown)
                assertEquals(o, AccountLogic.digitOffset(t, len))
            }
            for (t in 0..shown) assertTrue(AccountLogic.digitOffset(t, len) in 0..len)
        }
        // After "1234 " the cursor is after the 4th digit.
        assertEquals(4, AccountLogic.digitOffset(5, 8))
    }

    @Test
    fun recognisesInviteLinks() {
        val link = "https://omaid.app/j#n=4539148803436467&k=abcDEF_-"
        assertTrue(AccountLogic.isInviteLink(link))
        assertTrue(AccountLogic.isInviteLink("  $link \n"))
        assertTrue(AccountLogic.isInviteLink("https://omaid.app/j/#n=4539148803436467&k=x"))
        assertFalse(AccountLogic.isInviteLink("https://omaid.app/j?n=4539148803436467"))
        assertFalse(AccountLogic.isInviteLink("https://evil.example/j#n=4539148803436467"))
        assertFalse(AccountLogic.isInviteLink("https://omaid.app/jx#n=4539148803436467"))
        assertFalse(AccountLogic.isInviteLink("https://omaid.app/j#k=abc"))
        assertTrue(AccountLogic.isScannableLink(link))
        assertTrue(AccountLogic.isScannableLink("omasend://pair?v=2&pk=x"))
        assertFalse(AccountLogic.isScannableLink("4539148803436467"))
    }

    @Test
    fun onlyHttpsUrlsAreAllowed() {
        assertTrue(AccountLogic.isAllowedUrl("https://id.omaid.app/v1/account/abc"))
        assertFalse(AccountLogic.isAllowedUrl("http://id.omaid.app/v1/account/abc"))
        assertFalse(AccountLogic.isAllowedUrl("HTTP://id.omaid.app/"))
        assertFalse(AccountLogic.isAllowedUrl("https://user@id.omaid.app/"))
        assertFalse(AccountLogic.isAllowedUrl("https:///nohost"))
        assertFalse(AccountLogic.isAllowedUrl("file:///etc/passwd"))
    }

    @Test
    fun publishesOnlyPrivateLanAddresses() {
        assertEquals("192.168.1.5:53318", AccountLogic.lanAddr("192.168.1.5", 53318))
        assertEquals("10.0.0.2:53318", AccountLogic.lanAddr("10.0.0.2", 53318))
        assertNull(AccountLogic.lanAddr("127.0.0.1", 53318))
        assertNull(AccountLogic.lanAddr("169.254.3.4", 53318))
        assertNull(AccountLogic.lanAddr("8.8.8.8", 53318))
        assertNull(AccountLogic.lanAddr("100.64.1.1", 53318))
        assertNull(AccountLogic.lanAddr("192.168.1.5", 0))
        assertNull(AccountLogic.lanAddr("fe80::1", 53318))
    }

    @Test
    fun parsesAddressesAndBuildsDialHints() {
        assertEquals("192.168.1.9" to 53318, AccountLogic.parseHostPort("192.168.1.9:53318"))
        assertEquals("fd00::1" to 4000, AccountLogic.parseHostPort("[fd00::1]:4000"))
        assertNull(AccountLogic.parseHostPort("192.168.1.9"))
        assertNull(AccountLogic.parseHostPort("192.168.1.9:99999"))
        val d = AccountDeviceView(laptop, "Laptop", relay = "https://euw1-1.relay.iroh.network./", addrs = listOf("192.168.1.9:53318", "junk"))
        assertEquals(listOf("192.168.1.9:53318", "https://euw1-1.relay.iroh.network./"), AccountLogic.dialHints(d))
        assertEquals(listOf("192.168.1.9:53318"), AccountLogic.dialHints(d.copy(relay = "http://plain.example/")))
    }

    @Test
    fun mirrorsAccountDevicesAsOwnDevices() {
        val oldTablet = DeviceKey.TrustedKey(tablet, DeviceKey.deviceIdForPk(DeviceKey.decodePk(tablet)!!), "Tablet", ip = "192.168.1.20", quic_port = 53318, own_device = false, added_at = 5)
        val devices = listOf(
            AccountDeviceView(me, "Phone"),
            AccountDeviceView(laptop, "Laptop", addrs = listOf("192.168.1.9:53319")),
            AccountDeviceView(tablet, "Tablet")
        )
        val plan = AccountLogic.planTrust(me, devices, emptyList(), listOf(oldTablet), emptyList(), now = 100)
        assertEquals(2, plan.upsert.size)
        val l = plan.upsert.first { it.pk == laptop }
        assertTrue(l.own_device)
        assertEquals("192.168.1.9", l.ip)
        assertEquals(53319, l.quic_port)
        assertEquals(100L, l.added_at)
        val t = plan.upsert.first { it.pk == tablet }
        assertTrue(t.own_device)
        // No address published: the last known one is kept.
        assertEquals("192.168.1.20", t.ip)
        assertEquals(5L, t.added_at)
        assertEquals(listOf("Laptop", "Tablet"), plan.added)
        assertTrue(plan.upsert.none { it.pk == me })

        // Applying the same report again changes nothing.
        val again = AccountLogic.planTrust(me, devices, emptyList(), plan.upsert, emptyList(), now = 200)
        assertTrue(again.upsert.isEmpty())
        assertTrue(again.added.isEmpty())
    }

    @Test
    fun revokedKeysAreDroppedAndNeverReAdded() {
        val existing = listOf(DeviceKey.TrustedKey(laptop, "id", "Laptop", own_device = true, added_at = 1))
        val plan = AccountLogic.planTrust(
            me,
            listOf(AccountDeviceView(me, "Phone"), AccountDeviceView(laptop, "Laptop")),
            revoked = listOf(laptop, tablet, me),
            existing = existing,
            knownRevoked = listOf(tablet),
            now = 1
        )
        assertEquals(listOf(laptop), plan.newlyRevoked)
        assertEquals(listOf(laptop), plan.remove)
        assertTrue(plan.upsert.isEmpty())
    }

    @Test
    fun reportsWhatChanged() {
        val j1 = JoinView("r1", tablet, "Tablet", "123 456")
        val j2 = JoinView("r2", laptop, "Laptop", "654 321")
        val prev = AccountView(AccountPhase.MEMBER, "4539 1488 0343 6467", joins = listOf(j1))
        val next = prev.copy(joins = listOf(j1, j2), resetPending = true)
        val ch = AccountLogic.changes(prev, next)
        assertEquals(listOf(j2), ch.newJoins)
        assertTrue(ch.resetStarted)
        assertFalse(ch.joined)
        assertFalse(AccountLogic.changes(next, next).resetStarted)

        val waiting = AccountView(AccountPhase.WAITING, "4539 1488 0343 6467", myCode = "111 222")
        assertTrue(AccountLogic.changes(waiting, prev).joined)
        assertTrue(AccountLogic.changes(null, waiting).newJoins.isEmpty())
    }

    @Test
    fun syncsFasterWhileSomethingIsPending() {
        val member = AccountView(AccountPhase.MEMBER, "n")
        assertNull(AccountLogic.syncIntervalMs(null, inviteVisible = true, active = true))
        assertEquals(AccountLogic.INVITE_SYNC_MS, AccountLogic.syncIntervalMs(member, inviteVisible = true, active = false))
        assertEquals(AccountLogic.PENDING_SYNC_MS, AccountLogic.syncIntervalMs(member.copy(phase = AccountPhase.WAITING), false, false))
        assertEquals(AccountLogic.PENDING_SYNC_MS, AccountLogic.syncIntervalMs(member.copy(phase = AccountPhase.RESETTING), false, true))
        assertEquals(AccountLogic.PENDING_SYNC_MS, AccountLogic.syncIntervalMs(member.copy(joins = listOf(JoinView("r", tablet, "T", "1"))), false, true))
        assertEquals(AccountLogic.IDLE_SYNC_MS, AccountLogic.syncIntervalMs(member, false, active = true))
        assertNull(AccountLogic.syncIntervalMs(member, false, active = false))
    }

    @Test
    fun mapsCoreErrorsToMessages() {
        assertEquals("acct_err_unreachable", AccountLogic.errorKey("account server unreachable"))
        assertEquals("acct_err_no_account", AccountLogic.errorKey("no account uses this number"))
        assertEquals("acct_err_expired", AccountLogic.errorKey("the join request expired; try again"))
        assertEquals("acct_err_suspicious", AccountLogic.errorKey("two requests show the same code; reject both and try again"))
        assertEquals("acct_err_rate", AccountLogic.errorKey("too many attempts, try again in a minute"))
        assertEquals("acct_err_taken", AccountLogic.errorKey("this number already belongs to an account; join it instead"))
        assertNull(AccountLogic.errorKey("account server error 500: boom"))
        assertNull(AccountLogic.errorKey(null))
        assertTrue(AccountLogic.isTerminal(AccountPhase.WAITING, "the join request expired; try again"))
        assertTrue(AccountLogic.isTerminal(AccountPhase.RESETTING, "the reset was cancelled by one of the account's devices"))
        assertFalse(AccountLogic.isTerminal(AccountPhase.MEMBER, "the join request expired; try again"))
        assertFalse(AccountLogic.isTerminal(AccountPhase.WAITING, "account server unreachable"))
    }

    @Test
    fun readsPhaseAndNumberFromCoreState() {
        val member = AccountLogic.viewFromState("""{"v":1,"number":"4539 1488 0343 6467","account_sk":"aa","vault_key":"bb"}""", 7)!!
        assertEquals(AccountPhase.MEMBER, member.phase)
        assertEquals("4539 1488 0343 6467", member.number)
        assertEquals(7L, member.updatedAt)
        assertEquals(AccountPhase.WAITING, AccountLogic.viewFromState("""{"v":1,"number":"4539148803436467","rid":"r"}""", 0)!!.phase)
        val resetting = AccountLogic.viewFromState("""{"v":1,"number":"x","reset_sk":"cc","account_sk":""}""", 0)!!
        assertEquals(AccountPhase.RESETTING, resetting.phase)
        assertEquals(AccountLogic.RESET_WAIT_SECS, resetting.resetWait)
        assertNull(AccountLogic.viewFromState("not json", 0))
    }

    @Test
    fun roundsTheRemainingWaitUp() {
        assertEquals(24L to 0L, AccountLogic.hoursMinutes(24 * 3600))
        assertEquals(0L to 1L, AccountLogic.hoursMinutes(1))
        assertEquals(1L to 1L, AccountLogic.hoursMinutes(3601))
        assertEquals(0L to 0L, AccountLogic.hoursMinutes(-5))
    }
}
