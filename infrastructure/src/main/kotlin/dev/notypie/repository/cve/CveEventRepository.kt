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

/** Event count for one topic (the ops topic listing). Topics with zero events are absent from the result. */
data class TopicEventCount(
    val topicId: Long,
    val count: Long,
)

/** One DONE-summarized event projected for the `/latest` DM (newest first). */
data class CveRecentEvent(
    val topicDisplayName: String,
    val title: String,
    val aiSummary: String?,
)

/**
 * Read/CAS surface for the AI summary worker. Ingestion is idempotent via [insertIgnore];
 * multi-instance safety of the summary side rests entirely on the atomic claim-token CAS
 * ([claimForSummary]/[markDone]/[markFailed]) — mirrors the standup dispatch pattern.
 * [resetStuck] recovers rows a crashed worker left mid-flight.
 */
interface CveEventRepository {
    /**
     * Ingests one collected source event as PENDING, idempotently: the collector may re-fetch an
     * overlapping window, and `INSERT IGNORE` swallows the duplicate-key error on the existing
     * unique(topic_id, external_id) so a re-seen event is a no-op. Returns 1 when the row was newly
     * inserted, 0 when it already existed. The impl truncates [title] and [rawContent] to the column
     * limits before insert. Mirrors `JpaCveSubscriptionRepository.insertIgnore`.
     */
    fun insertIgnore(
        topicId: Long,
        externalId: String,
        title: String,
        rawContent: String,
        publishedAt: LocalDateTime?,
    ): Int

    /**
     * Events eligible for a summary attempt: PENDING or FAILED, below [maxRetries], whose backoff
     * (if any) has elapsed by [now]. Ordered by id, capped at [limit].
     */
    fun findClaimable(now: LocalDateTime, maxRetries: Int, limit: Int): List<CveEvent>

    /**
     * Atomically claims [id] for this worker by stamping [token] and flipping to SUMMARIZING,
     * only while the row is still claimable at [now] and its retry count is under [maxRetries]
     * (a stale candidate must not revive a row dead-lettered since it was read). Returns 1 when
     * this call won the row, 0 when another instance already claimed it.
     */
    fun claimForSummary(
        id: Long,
        token: String,
        now: LocalDateTime,
        maxRetries: Int,
    ): Int

    /**
     * Records [summary] and marks the row DONE, guarded by the owning [token], stamping updated_at
     * from the app clock [now] (the dispatcher's digest cutoff compares against it). Returns rows
     * affected.
     */
    fun markDone(
        id: Long,
        token: String,
        summary: String,
        now: LocalDateTime,
    ): Int

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

    /** Count of events in [status] (ops status report). */
    fun countByStatus(status: CveSummaryStatus): Long

    /** Count of FAILED events still under the retry budget (retryCount < [maxRetries]). */
    fun countFailedRetryable(maxRetries: Int): Long

    /** Count of dead-letter events: FAILED and out of retry budget (retryCount >= [maxRetries]). */
    fun countDeadLetter(maxRetries: Int): Long

    /** Total event counts for [topicIds]; topics with no events are omitted. Empty input returns empty. */
    fun countEventsByTopic(topicIds: List<Long>): List<TopicEventCount>

    /**
     * The most recent DONE-summarized events across [topicIds], joined to their topic display name,
     * newest first (id DESC), capped at [limit]. Empty [topicIds] returns empty. DB-only — no AI call.
     */
    fun findRecentDoneEvents(topicIds: List<Long>, limit: Int): List<CveRecentEvent>

    /**
     * Revives every dead-letter event (FAILED, retryCount >= [maxRetries]) back to PENDING with the
     * retry budget reset and any claim/backoff cleared, so the summary worker re-drives it on its next
     * tick. The WHERE guard resets only true dead-letters. Returns the number of rows revived.
     */
    fun resetDeadLetters(maxRetries: Int): Int

    /**
     * Revives one dead-letter event [id] (same guard as [resetDeadLetters]). Returns 0 when [id] is not
     * a dead-letter (unknown id, wrong status, or still within budget), so the caller can report it.
     */
    fun resetDeadLetter(id: Long, maxRetries: Int): Int
}
