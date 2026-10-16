package io.omarchy.omasend

import io.omarchy.omasend.model.IncomingTransferPrompt
import io.omarchy.omasend.model.TransferFileInfo
import io.omarchy.omasend.network.PendingTransferRegistry
import io.omarchy.omasend.network.PendingTransferRegistry.AddResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingTransferRegistryTest {
    private var now = 1_000_000L
    private val registry = PendingTransferRegistry(maxPendingPerSource = 3, maxTotal = 5, clock = { now })

    private fun entry(ip: String, files: List<TransferFileInfo> = listOf(TransferFileInfo("a.txt", 1024)), status: String = PendingTransferRegistry.STATUS_PENDING) =
        PendingTransferRegistry.Entry(
            prompt = IncomingTransferPrompt("t", "sender", "Sender", ip, files, files.sumOf { it.size_bytes }),
            sourceIp = ip,
            status = status,
            expiresAt = now + PendingTransferRegistry.PENDING_TTL_MS
        )

    @Test
    fun acceptingExtendsTheLifetime() {
        registry.add("tok", entry("10.0.0.2"))
        now += PendingTransferRegistry.PENDING_TTL_MS - 1_000 // accepted 1 s before the request window ends
        val accepted = registry.accept("tok")
        assertNotNull(accepted)
        assertEquals(PendingTransferRegistry.STATUS_ACCEPTED, accepted!!.status)
        now += 60_000 // the upload starts a minute later
        assertFalse(registry.isExpired(accepted))
        assertTrue(registry.isActive("tok"))
    }

    @Test
    fun refreshNeverShortensAndCoversTheUpload() {
        registry.add("tok", entry("10.0.0.2"))
        val e = registry.accept("tok")!!
        val before = e.expiresAt
        registry.refresh(e, now + 1_000)
        assertTrue(e.expiresAt >= before)
        registry.refresh(e, now + 60 * 60_000L)
        assertEquals(now + 60 * 60_000L, e.expiresAt)
    }

    @Test
    fun uploadsMustMatchAnApprovedNotYetUploadedFile() {
        val files = listOf(TransferFileInfo("a.txt", 1024), TransferFileInfo("b.txt", 10))
        val e = entry("10.0.0.2", files)
        assertEquals(-1, e.claim("x.apk", 9L * 1024 * 1024 * 1024))
        assertEquals(-1, e.claim("a.txt", 9L * 1024 * 1024 * 1024)) // right name, wrong size
        val a = e.claim("a.txt", 1024)
        assertEquals(0, a)
        assertEquals(-1, e.claim("a.txt", 1024)) // already in flight
        assertFalse(e.complete(a))
        assertEquals(-1, e.claim("a.txt", 1024)) // already received
        val b = e.claim("b.txt", 10)
        e.release(b) // failed upload: may be retried
        val b2 = e.claim("b.txt", 10)
        assertEquals(1, b2)
        assertTrue(e.complete(b2))
    }

    @Test
    fun duplicateNamesAreEachAllowedOnce() {
        val e = entry("10.0.0.2", listOf(TransferFileInfo("img.jpg", 5), TransferFileInfo("img.jpg", 5)))
        assertEquals(0, e.claim("img.jpg", 5))
        assertEquals(1, e.claim("img.jpg", 5))
        assertEquals(-1, e.claim("img.jpg", 5))
    }

    @Test
    fun pendingRequestsAreCappedPerSourceAndGlobally() {
        repeat(3) { assertEquals(AddResult.Added, registry.add("a$it", entry("10.0.0.2"))) }
        assertEquals(AddResult.TooManyFromSource, registry.add("a3", entry("10.0.0.2")))
        assertEquals(AddResult.Added, registry.add("b0", entry("10.0.0.3")))
        assertEquals(AddResult.Added, registry.add("b1", entry("10.0.0.3")))
        assertEquals(AddResult.TooMany, registry.add("c0", entry("10.0.0.4")))
    }

    @Test
    fun expiredEntriesArePurgedOnInsert() {
        repeat(3) { registry.add("a$it", entry("10.0.0.2")) }
        now += PendingTransferRegistry.PENDING_TTL_MS + 1
        assertEquals(AddResult.Added, registry.add("fresh", entry("10.0.0.2")))
        assertEquals(1, registry.size())
    }
}
