package dev.notypie.application.security

import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

data class SlackRequestFingerprint(
    val method: String,
    val requestUri: String,
    val timestamp: String,
    val signature: String,
)

interface SlackRetryDeduplicator {
    fun isDuplicateRetry(fingerprint: SlackRequestFingerprint, retryNum: String?): Boolean
}

class InMemorySlackRetryDeduplicator(
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMinutes(10),
) : SlackRetryDeduplicator {
    private val seen = ConcurrentHashMap<SlackRequestFingerprint, Long>()

    override fun isDuplicateRetry(fingerprint: SlackRequestFingerprint, retryNum: String?): Boolean {
        val now = clock.millis()
        evictExpired(now = now)

        val previous = seen.putIfAbsent(fingerprint, now)
        return previous != null && retryNum != null
    }

    private fun evictExpired(now: Long) {
        val cutoff = now - ttl.toMillis()
        seen.entries.removeIf { (_, seenAt) -> seenAt < cutoff }
    }
}
