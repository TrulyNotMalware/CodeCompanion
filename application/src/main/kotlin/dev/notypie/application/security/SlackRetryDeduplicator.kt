package dev.notypie.application.security

import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// Keyed by the body, never by X-Slack-Request-Timestamp / X-Slack-Signature: Slack signs every retry afresh,
// so those headers differ per attempt while the payload (and its event_id) stays byte-identical.
data class SlackRequestFingerprint(
    val method: String,
    val requestUri: String,
    val bodyHash: String,
) {
    companion object {
        fun of(method: String, requestUri: String, body: ByteArray): SlackRequestFingerprint =
            SlackRequestFingerprint(
                method = method,
                requestUri = requestUri,
                bodyHash = MessageDigest.getInstance("SHA-256").digest(body).toHexString(),
            )
    }
}

interface SlackRetryDeduplicator {
    // True when the request is a Slack retry of one already accepted (completed or still running), so the
    // filter answers 200 without dispatching again. A first attempt is always admitted and recorded.
    fun isDuplicateRetry(fingerprint: SlackRequestFingerprint, retryNum: String?): Boolean

    // A failed attempt is forgotten so the retry Slack sends after a 5xx is processed, not acknowledged away.
    fun markFailed(fingerprint: SlackRequestFingerprint)
}

// Single-replica only: the map lives in this JVM, so a retry routed to another Pod is not recognised.
class InMemorySlackRetryDeduplicator(
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMinutes(10),
    private val maxEntries: Int = 10_000,
) : SlackRetryDeduplicator {
    private val seen = ConcurrentHashMap<SlackRequestFingerprint, Long>()
    private val lastSweepAt = AtomicLong(0L)

    override fun isDuplicateRetry(fingerprint: SlackRequestFingerprint, retryNum: String?): Boolean {
        val now = clock.millis()
        sweepIfDue(now = now)
        val previous = seen.putIfAbsent(fingerprint, now)
        if (seen.size > maxEntries) trimToCap()
        return previous != null && retryNum != null
    }

    internal fun trackedEntries(): Int = seen.size

    override fun markFailed(fingerprint: SlackRequestFingerprint) {
        seen.remove(fingerprint)
    }

    // Sweeping is O(n); amortise it to once per half TTL.
    private fun sweepIfDue(now: Long) {
        val last = lastSweepAt.get()
        if (now - last < ttl.toMillis() / 2 || !lastSweepAt.compareAndSet(last, now)) return
        val cutoff = now - ttl.toMillis()
        seen.entries.removeIf { (_, seenAt) -> seenAt < cutoff }
    }

    private fun trimToCap() {
        val snapshot = seen.entries.sortedBy { (_, seenAt) -> seenAt }
        snapshot
            .take((snapshot.size - maxEntries).coerceAtLeast(0))
            .forEach { (key, _) -> seen.remove(key) }
    }
}
