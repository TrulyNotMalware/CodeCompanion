package dev.notypie.application.security

import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

data class SlackRequestFingerprint(
    val method: String,
    val requestPath: String,
    val bodyHash: String,
) {
    companion object {
        fun of(method: String, requestPath: String, body: ByteArray): SlackRequestFingerprint =
            SlackRequestFingerprint(
                method = method,
                requestPath = requestPath,
                bodyHash = MessageDigest.getInstance("SHA-256").digest(body).toHexString(),
            )
    }
}

data class SlackRetryTicket(
    val fingerprint: SlackRequestFingerprint,
    val generation: Long,
)

sealed interface SlackRetryAdmission {
    data class FirstAttempt(
        val ticket: SlackRetryTicket,
    ) : SlackRetryAdmission

    data object Untracked : SlackRetryAdmission

    data object RetryOfInFlight : SlackRetryAdmission

    data object RetryOfCompleted : SlackRetryAdmission
}

interface SlackRetryDeduplicator {
    fun admit(fingerprint: SlackRequestFingerprint, retryNum: String?): SlackRetryAdmission

    fun markCompleted(ticket: SlackRetryTicket)

    fun markFailed(ticket: SlackRetryTicket)
}

class InMemorySlackRetryDeduplicator(
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMinutes(10),
    private val maxEntries: Int = 10_000,
) : SlackRetryDeduplicator {
    private enum class State { IN_FLIGHT, COMPLETED }

    private data class Entry(
        val state: State,
        val recordedAt: Long,
        val generation: Long,
    )

    private val entries = ConcurrentHashMap<SlackRequestFingerprint, Entry>()
    private val generations = AtomicLong(0L)
    private val lastSweepAt = AtomicLong(0L)
    private val trimLock = ReentrantLock()

    // Set by every completion and by a trim that left COMPLETED entries behind; cleared when a trim scan starts.
    // While it is false the map holds no COMPLETED entry a scan could remove, so a full map of in-flight entries
    // does not cost every new request a filter + sort over all of them.
    private val completedEntriesMayRemain = AtomicBoolean(false)
    private val trimScans = AtomicLong(0L)
    private val trimTarget: Int

    init {
        require(maxEntries > 0) { "maxEntries must be positive: $maxEntries" }
        trimTarget = maxEntries * 9 / 10
    }

    override fun admit(fingerprint: SlackRequestFingerprint, retryNum: String?): SlackRetryAdmission {
        val now = clock.millis()
        sweepIfDue(now = now)
        if (entries.size >= maxEntries) trimCompleted()
        var admission: SlackRetryAdmission = SlackRetryAdmission.Untracked
        entries.compute(fingerprint) { _, existing ->
            when {
                existing == null && entries.size >= maxEntries -> {
                    admission = SlackRetryAdmission.Untracked
                    null
                }

                existing == null || isExpired(entry = existing, now = now) -> {
                    val ticket = SlackRetryTicket(fingerprint = fingerprint, generation = generations.incrementAndGet())
                    admission = SlackRetryAdmission.FirstAttempt(ticket = ticket)
                    Entry(state = State.IN_FLIGHT, recordedAt = now, generation = ticket.generation)
                }

                // Judged on the entry alone, with or without X-Slack-Retry-Num: Slack never resends an
                // identical body (event_id is unique) without that header, so a header-less copy is a replay.
                existing.state == State.IN_FLIGHT -> {
                    admission = SlackRetryAdmission.RetryOfInFlight
                    existing
                }

                else -> {
                    admission = SlackRetryAdmission.RetryOfCompleted
                    existing
                }
            }
        }
        return admission
    }

    override fun markCompleted(ticket: SlackRetryTicket) {
        entries.computeIfPresent(ticket.fingerprint) { _, entry ->
            if (entry.generation == ticket.generation) {
                entry.copy(state = State.COMPLETED, recordedAt = clock.millis())
            } else {
                entry
            }
        }
        completedEntriesMayRemain.set(true)
    }

    override fun markFailed(ticket: SlackRetryTicket) {
        entries.computeIfPresent(ticket.fingerprint) { _, entry ->
            if (entry.generation == ticket.generation) null else entry
        }
    }

    internal fun trackedEntries(): Int = entries.size

    internal fun trimScans(): Long = trimScans.get()

    private fun isExpired(entry: Entry, now: Long): Boolean = entry.recordedAt < now - ttl.toMillis()

    private fun sweepIfDue(now: Long) {
        val last = lastSweepAt.get()
        if (now - last < ttl.toMillis() / 2 || !lastSweepAt.compareAndSet(last, now)) return
        entries.entries.removeIf { (_, entry) -> isExpired(entry = entry, now = now) }
    }

    private fun trimCompleted() {
        if (!trimLock.tryLock()) return
        try {
            val excess = entries.size - trimTarget
            if (excess <= 0 || !completedEntriesMayRemain.getAndSet(false)) return
            trimScans.incrementAndGet()
            val completed = entries.entries.filter { (_, entry) -> entry.state == State.COMPLETED }
            completed
                .sortedBy { (_, entry) -> entry.recordedAt }
                .take(excess)
                .forEach { (fingerprint, entry) -> entries.remove(fingerprint, entry) }
            if (completed.size > excess) completedEntriesMayRemain.set(true)
        } finally {
            trimLock.unlock()
        }
    }
}
