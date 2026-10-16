package io.omarchy.omasend

import io.omarchy.omasend.network.IncomingCancellation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCancellationTest {

    @Test
    fun cancelHitsOnlyTheLatestTransfer() {
        val c = IncomingCancellation()
        val first = c.begin()
        val second = c.begin()
        c.cancelLatest()
        assertTrue(c.isCancelled(second))
        assertFalse(c.isCancelled(first))
    }

    @Test
    fun newTransferDoesNotClearAPendingCancel() {
        val c = IncomingCancellation()
        val first = c.begin()
        c.cancelLatest()
        val second = c.begin()
        assertTrue(c.isCancelled(first))
        assertFalse(c.isCancelled(second))
    }

    @Test
    fun cancelAfterEndIsIgnored() {
        val c = IncomingCancellation()
        val id = c.begin()
        c.end(id)
        c.cancel(id)
        assertFalse(c.isCancelled(id))
    }

    @Test
    fun cancelWithNoTransferIsANoOp() {
        val c = IncomingCancellation()
        c.cancelLatest()
        val id = c.begin()
        assertFalse(c.isCancelled(id))
    }
}
