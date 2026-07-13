package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveSummaryStatus
import java.time.LocalDateTime

/** One ingested source event as seen by the summarize-once worker. */
data class CveEvent(
    val id: Long,
    val topicId: Long,
    val externalId: String,
    val title: String,
    val rawContent: String,
    val aiSummary: String?,
    val summaryStatus: CveSummaryStatus,
    val retryCount: Int,
)

/**
 * Read/CAS surface for the AI summary worker. Multi-instance safety rests entirely on the
 * atomic claim-token CAS ([claimForSummary]/[markDone]/[markFailed]) — mirrors the standup
 * dispatch pattern. [resetStuck] recovers rows a crashed worker left mid-flight.
 */
interface CveEventRepository {
    /**
     * Events eligible for a summary attempt: PENDING or FAILED, below [maxRetries], whose backoff
     * (if any) has elapsed by [now]. Ordered by id, capped at [limit].
     */
    fun findClaimable(now: LocalDateTime, maxRetries: Int, limit: Int): List<CveEvent>

    /**
     * Atomically claims [id] for this worker by stamping [token] and flipping to SUMMARIZING,
     * only while the row is still claimable at [now]. Returns 1 when this call won the row, 0
     * when another instance already claimed it.
     */
    fun claimForSummary(id: Long, token: String, now: LocalDateTime): Int

    /** Records [summary] and marks the row DONE, guarded by the owning [token]. Returns rows affected. */
    fun markDone(id: Long, token: String, summary: String): Int

    /**
     * Marks the row FAILED, increments retry_count and schedules the next attempt at [nextAttemptAt],
     * guarded by the owning [token]. Returns rows affected.
     */
    fun markFailed(id: Long, token: String, nextAttemptAt: LocalDateTime): Int

    /**
     * Returns the claimed row to PENDING without consuming the retry budget (backpressure such as a
     * busy sidecar is not a failure), delaying the next attempt to [nextAttemptAt]. Guarded by the
     * owning [token]. Returns rows affected.
     */
    fun releaseClaim(id: Long, token: String, nextAttemptAt: LocalDateTime): Int

    /** Returns SUMMARIZING rows untouched since [olderThan] to PENDING (crash recovery). Returns count. */
    fun resetStuck(olderThan: LocalDateTime): Int
}
