package io.omarchy.omasend

import io.omarchy.omasend.crypto.DeviceKey
import io.omarchy.omasend.crypto.OmaLink
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Cross-language compatibility with the desktop engine (src/omalink.rs, PROTOCOL_V2.md).
 * If any value here changes, desktop and Android can no longer link.
 */
class OmaLinkTest {
    private val id = "4539-1488-0343-6467"
    private val wrongId = "4539-1488-0343-6475"

    @Test
    fun matchesRustVector() {
        assertEquals("02bf776f8c956317d2da49785228d6fd711af8a8ee32cb7aba70fd55925d94598e", DeviceKey.hex(OmaLink.encode(OmaLink.M)))
        assertEquals("02eecd84e595f27077112ab183f037c87e132d36742eca41580dbfa80114b12f07", DeviceKey.hex(OmaLink.encode(OmaLink.N)))
        assertEquals(
            "8e139c2abae6f62f14b23470a7bc2e504ab4c23e00bc9e1361044ca4554d7a06",
            DeviceKey.hex(OmaLink.scalarBytes(OmaLink.passwordScalar(id)))
        )
        val ini = OmaLink.Initiator(id, BigInteger.valueOf(7))
        val (pB, rk) = OmaLink.respond(id, ini.pA, BigInteger.valueOf(11)).getOrThrow()
        val ik = ini.finish(pB, rk.cB).getOrThrow()
        assertEquals("0230f5731715331f68be2bc69c91e0e3af2c416f559a571bcb6b9faa149e02cf1b", DeviceKey.hex(ini.pA))
        assertEquals("03ee51e9a4fcceb1b5a8900b4877dc7fc6d13bf10c9451c2e15a2eac27a39e9d18", DeviceKey.hex(pB))
        assertEquals("b2977414cbce7c32aff0226f3a19c90d80e5e5b778b461f69e60242dbdcf56b0", DeviceKey.hex(ik.cA))
        assertEquals("0f52e972837c60c635948d111d5b2161d7119281d537c8bc89e6c36efdd01a44", DeviceKey.hex(ik.cB))
        assertEquals("f28b858430952bdbde5c49716c49a9df60d6346e57bd7f042d2710ae5eb9b026", DeviceKey.hex(ik.boxKey))
        assertArrayEquals(rk.boxKey, ik.boxKey)
    }

    @Test
    fun formattingDoesNotMatterButDigitsDo() {
        var ini = OmaLink.Initiator("4539148803436467")
        var (pB, rk) = OmaLink.respond(id, ini.pA).getOrThrow()
        assertTrue(ini.finish(pB, rk.cB).isSuccess)

        ini = OmaLink.Initiator(wrongId)
        OmaLink.respond(id, ini.pA).getOrThrow().let { (b, k) -> pB = b; rk = k }
        assertTrue(ini.finish(pB, rk.cB).isFailure)
    }

    @Test
    fun rejectsInvalidPoints() {
        assertTrue(OmaLink.respond(id, ByteArray(33)).isFailure)
        assertTrue(OmaLink.respond(id, ByteArray(5) { 2 }).isFailure)
        assertTrue(OmaLink.Initiator(id).finish(ByteArray(33), ByteArray(32)).isFailure)
    }

    private val me = DeviceKey.Identity(ByteArray(32) { 1 })
    private val them = DeviceKey.Identity(ByteArray(32) { 2 })

    private fun fullRun(r: OmaLink.Responder, initId: String, ip: String, now: Long): Result<OmaLink.LinkedPeer> = runCatching {
        val ini = OmaLink.Initiator(initId)
        val pA = DeviceKey.b64(ini.pA)
        val start = when (val s = r.start(id, ip, pA, now)) {
            is OmaLink.StartResult.Ok -> s.body
            OmaLink.StartResult.RateLimited -> error("rate limited")
            is OmaLink.StartResult.Bad -> error(s.message)
        }
        val pB = start["pB"]!!.jsonPrimitive.content
        val keys = ini.finish(DeviceKey.unb64(pB)!!, DeviceKey.unb64(start["cB"]!!.jsonPrimitive.content)!!).getOrThrow()
        val body = buildJsonObject {
            put("sid", start["sid"]!!.jsonPrimitive.content)
            put("cA", DeviceKey.b64(keys.cA))
            put("box", OmaLink.initiatorBox(me, keys, pA, pB, "phone", 53318))
        }
        val (peer, reply) = r.finish(them, "desk", 53319, ip, body, now).getOrThrow()
        assertEquals(me.publicB64, peer.pk)
        assertEquals("phone", peer.name)
        OmaLink.openResponderBox(keys, pA, pB, me.publicB64, reply["box"]!!.jsonPrimitive.content).getOrThrow()
    }

    @Test
    fun endToEndAndRefund() {
        val r = OmaLink.Responder()
        repeat(10) {
            val back = fullRun(r, id, "192.168.1.20", 1_000L).getOrThrow()
            assertEquals(them.publicB64, back.pk)
            assertEquals("desk", back.name)
            assertEquals(53319, back.quicPort)
        }
        assertEquals(0, r.attemptCount)
    }

    @Test
    fun wrongGuessesAreBudgeted() {
        val r = OmaLink.Responder()
        repeat(OmaLink.MAX_ATTEMPTS_PER_IP) {
            assertEquals("OmaID does not match", fullRun(r, wrongId, "10.0.0.9", 0L).exceptionOrNull()?.message)
        }
        assertEquals("rate limited", fullRun(r, id, "10.0.0.9", 0L).exceptionOrNull()?.message)
        assertTrue(fullRun(r, id, "10.0.0.10", 0L).isSuccess)
        assertTrue(fullRun(r, id, "10.0.0.9", OmaLink.RATE_WINDOW_MS).isSuccess)
    }

    @Test
    fun boxesAreBoundToDirection() {
        val ini = OmaLink.Initiator(id)
        val (pB, rk) = OmaLink.respond(id, ini.pA).getOrThrow()
        val keys = ini.finish(pB, rk.cB).getOrThrow()
        val sealed = OmaLink.initiatorBox(me, keys, DeviceKey.b64(ini.pA), DeviceKey.b64(pB), "x", 0)
        assertTrue(OmaLink.open(rk.boxKey, "I", sealed).isSuccess)
        assertTrue(OmaLink.open(rk.boxKey, "R", sealed).isFailure)
    }

    private fun own(id: DeviceKey.Identity, name: String) = DeviceKey.TrustedKey(
        pk = id.publicB64, device_id = id.deviceId, name = name, ip = "192.168.1.5", port = 53317, own_device = true, added_at = 1
    )

    @Test
    fun rosterJoinsDevicesAndRevocationSpreads() {
        val a = DeviceKey.Identity(ByteArray(32) { 11 })
        val b = DeviceKey.Identity(ByteArray(32) { 12 })
        val c = DeviceKey.Identity(ByteArray(32) { 13 })
        val sender = own(a, "A")
        val body = OmaLink.rosterBody(a, "A", 53317, 53318, listOf(own(b, "B"), own(c, "C")), emptyList())

        val plan = OmaLink.planRoster(b.publicB64, sender, "192.168.1.9", body, emptyList(), listOf(sender), 100).getOrThrow()
        assertEquals(listOf("C"), plan.added)
        assertEquals(setOf(a.publicB64, c.publicB64), plan.upsert.map { it.pk }.toSet())
        assertEquals("192.168.1.9", plan.upsert.first { it.pk == a.publicB64 }.ip)
        assertTrue(plan.upsert.all { it.own_device })
        assertTrue(!plan.rotate && !plan.selfRevoked)

        // A removes C: B drops it, rotates its number and never re-adds it.
        val afterRevoke = OmaLink.rosterBody(a, "A", 53317, 53318, listOf(own(b, "B")), listOf(c.publicB64))
        val existing = listOf(sender, own(c, "C"))
        val p2 = OmaLink.planRoster(b.publicB64, sender, "192.168.1.9", afterRevoke, emptyList(), existing, 100).getOrThrow()
        assertEquals(listOf(c.publicB64), p2.remove)
        assertTrue(p2.rotate)
        val stale = OmaLink.planRoster(b.publicB64, sender, "192.168.1.9", body, p2.revoked, listOf(sender), 100).getOrThrow()
        assertTrue(stale.upsert.none { it.pk == c.publicB64 })

        // The removed device forgets the account.
        val p3 = OmaLink.planRoster(c.publicB64, sender, "192.168.1.9", afterRevoke, emptyList(), listOf(sender, own(b, "B")), 100).getOrThrow()
        assertTrue(p3.selfRevoked)
        assertEquals(setOf(a.publicB64, b.publicB64), p3.remove.toSet())
        assertTrue(p3.upsert.isEmpty())
    }

    @Test
    fun rosterOnlyFromOwnDevices() {
        val friend = own(DeviceKey.Identity(ByteArray(32) { 14 }), "friend").copy(own_device = false)
        val body = OmaLink.rosterBody(DeviceKey.Identity(ByteArray(32) { 14 }), "friend", 53317, 0, emptyList(), emptyList())
        assertTrue(OmaLink.planRoster("x", friend, "10.0.0.1", body, emptyList(), emptyList(), 1).isFailure)
    }
}
