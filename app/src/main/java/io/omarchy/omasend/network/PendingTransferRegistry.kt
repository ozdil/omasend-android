package io.omarchy.omasend.network

import io.omarchy.omasend.model.IncomingTransferPrompt
import java.util.concurrent.ConcurrentHashMap

/**
 * Legacy HTTP transfers between the request (`/api/p2p/request`) and the last upload.
 *
 * - A9: expired entries are purged on every insert, and the number of open requests is capped
 *   per source IP and globally, so a LAN peer cannot grow the map without bound.
 * - A7: accepting a transfer extends its lifetime (the 30 s request window used to keep running,
 *   so a transfer accepted near the end got 403 on upload); each upload refreshes it again.
 * - A8: an upload must match a file of the approved request (same name and size) that has not
 *   been uploaded yet, so approving "a.txt, 1 KB" does not admit a 9 GB "x.apk".
 */
class PendingTransferRegistry(
    private val maxPendingPerSource: Int = MAX_PENDING_PER_SOURCE,
    private val maxTotal: Int = MAX_TOTAL,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_ACCEPTED = "ACCEPTED"
        const val STATUS_REJECTED = "REJECTED"

        const val PENDING_TTL_MS = 30_000L
        const val ACCEPTED_TTL_MS = 10 * 60_000L
        const val MAX_PENDING_PER_SOURCE = 3
        const val MAX_TOTAL = 32
    }

    class Entry(
        val prompt: IncomingTransferPrompt,
        /** Socket address the request came from (not the self-reported sender_ip). */
        val sourceIp: String,
        status: String,
        expiresAt: Long,
        /** v2: key of the verified sender; uploads must be signed by the same key. */
        val senderPk: String? = null
    ) {
        @Volatile
        var status: String = status
            internal set

        @Volatile
        var expiresAt: Long = expiresAt
            internal set

        private val claimed = BooleanArray(prompt.files.size)
        private val done = BooleanArray(prompt.files.size)

        /**
         * Reserves the first approved, not yet uploaded (or in-flight) file with this exact name
         * and size. Returns its index, or -1 when the upload is not part of the approval.
         */
        @Synchronized
        fun claim(name: String, size: Long): Int {
            val i = prompt.files.indices.firstOrNull { idx ->
                !claimed[idx] && !done[idx] &&
                    prompt.files[idx].name == name && prompt.files[idx].size_bytes == size
            } ?: return -1
            claimed[i] = true
            return i
        }

        /** Gives a claimed file back after a failed upload so the sender can retry it. */
        @Synchronized
        fun release(index: Int) {
            if (index in claimed.indices && !done[index]) claimed[index] = false
        }

        /** Marks a claimed file as received; true when every approved file has arrived. */
        @Synchronized
        fun complete(index: Int): Boolean {
            if (index in done.indices) done[index] = true
            return done.all { it }
        }
    }

    sealed class AddResult {
        object Added : AddResult()
        object TooManyFromSource : AddResult()
        object TooMany : AddResult()
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    fun isExpired(entry: Entry): Boolean = clock() > entry.expiresAt

    @Synchronized
    fun add(token: String, entry: Entry): AddResult {
        purgeExpired()
        if (entry.status == STATUS_PENDING &&
            entries.values.count { it.sourceIp == entry.sourceIp && it.status == STATUS_PENDING } >= maxPendingPerSource
        ) {
            return AddResult.TooManyFromSource
        }
        if (entries.size >= maxTotal) return AddResult.TooMany
        entries[token] = entry
        return AddResult.Added
    }

    fun get(token: String): Entry? = entries[token]

    /** Present and not expired. */
    fun isActive(token: String): Boolean = entries[token]?.let { !isExpired(it) } == true

    fun remove(token: String) {
        entries.remove(token)
    }

    /** User approved: the sender gets [ACCEPTED_TTL_MS] to start uploading. */
    fun accept(token: String): Entry? {
        val e = entries[token] ?: return null
        e.status = STATUS_ACCEPTED
        e.expiresAt = maxOf(e.expiresAt, clock() + ACCEPTED_TTL_MS)
        return e
    }

    fun reject(token: String) {
        entries[token]?.status = STATUS_REJECTED
    }

    /** Keeps an accepted transfer alive while uploads arrive (at least until [untilAtLeast]). */
    fun refresh(entry: Entry, untilAtLeast: Long) {
        entry.expiresAt = maxOf(entry.expiresAt, clock() + ACCEPTED_TTL_MS, untilAtLeast)
    }

    @Synchronized
    fun purgeExpired() {
        val now = clock()
        entries.entries.removeIf { now > it.value.expiresAt }
    }

    fun size(): Int = entries.size
}
