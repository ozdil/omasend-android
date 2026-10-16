package io.omarchy.omasend.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-transfer cancel tokens for incoming streams (V2-FUNC-02).
 *
 * A single global flag was reset by every new stream, so with two concurrent receives a
 * cancel could be wiped out or hit the wrong transfer. Each stream now gets its own id and
 * only that id is cancelled. [cancelLatest] targets the most recently started stream, which
 * is the one the UI shows.
 */
class IncomingCancellation {
    private val nextId = AtomicLong(0)
    private val active = ConcurrentHashMap.newKeySet<Long>()
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()

    @Volatile
    private var latest: Long = 0

    fun begin(): Long {
        val id = nextId.incrementAndGet()
        active.add(id)
        latest = id
        return id
    }

    fun cancel(id: Long) {
        if (id in active) cancelled.add(id)
    }

    fun cancelLatest() = cancel(latest)

    fun isCancelled(id: Long): Boolean = id in cancelled

    fun end(id: Long) {
        active.remove(id)
        cancelled.remove(id)
    }
}
