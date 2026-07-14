package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveEventSchema
import dev.notypie.repository.cve.schema.CveSummaryStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Repository
interface JpaCveEventRepository : JpaRepository<CveEventSchema, Long> {
    /**
     * Atomically ingests one source event. `INSERT IGNORE` swallows the duplicate-key error on
     * unique(topic_id, external_id), so re-collecting an overlapping window is a no-op; the
     * affected-row count (1 = inserted, 0 = already present) is the ingestion signal — no
     * check-then-act race. Mirrors `JpaCveSubscriptionRepository.insertIgnore`.
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT IGNORE INTO cve_event
                (topic_id, external_id, title, raw_content, published_at,
                 summary_status, retry_count, created_at)
            VALUES (:topicId, :externalId, :title, :rawContent, :publishedAt,
                    'PENDING', 0, CURRENT_TIMESTAMP(6))
        """,
        nativeQuery = true,
    )
    fun insertIgnore(
        @Param("topicId") topicId: Long,
        @Param("externalId") externalId: String,
        @Param("title") title: String,
        @Param("rawContent") rawContent: String,
        @Param("publishedAt") publishedAt: LocalDateTime?,
    ): Int

    @Query(
        """
        SELECT e FROM cve_event e
        WHERE e.summaryStatus IN (
            dev.notypie.repository.cve.schema.CveSummaryStatus.PENDING,
            dev.notypie.repository.cve.schema.CveSummaryStatus.FAILED
        )
          AND e.retryCount < :maxRetries
          AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= :now)
        ORDER BY e.id ASC
        """,
    )
    fun findClaimable(
        @Param("now") now: LocalDateTime,
        @Param("maxRetries") maxRetries: Int,
        pageable: Pageable,
    ): List<CveEventSchema>

    // updated_at is set explicitly here (and in every CAS below): native bulk updates bypass the
    // entity lifecycle, so Hibernate's @UpdateTimestamp never fires — resetStuck relies on it.
    // The claim stamps updated_at from the app clock (:now), not the DB clock, because resetStuck
    // compares it against an app-clock threshold — mixing clock sources would skew stuck detection.
    // markDone stamps it from :now for the same reason: the notification dispatcher's digest cutoff
    // compares a DONE row's updated_at against an app-clock send time.
    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE cve_event
            SET summary_status = 'SUMMARIZING', claim_token = :token, updated_at = :now
            WHERE id = :id AND summary_status IN ('PENDING', 'FAILED')
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
        """,
        nativeQuery = true,
    )
    fun claimForSummary(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("now") now: LocalDateTime,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE cve_event
            SET summary_status = 'PENDING', claim_token = NULL, next_attempt_at = :nextAttemptAt,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND summary_status = 'SUMMARIZING' AND claim_token = :token
        """,
        nativeQuery = true,
    )
    fun releaseClaim(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("nextAttemptAt") nextAttemptAt: LocalDateTime,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE cve_event
            SET summary_status = 'DONE', ai_summary = :summary, claim_token = NULL,
                updated_at = :now
            WHERE id = :id AND summary_status = 'SUMMARIZING' AND claim_token = :token
        """,
        nativeQuery = true,
    )
    fun markDone(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("summary") summary: String,
        @Param("now") now: LocalDateTime,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE cve_event
            SET summary_status = 'FAILED', retry_count = retry_count + 1, next_attempt_at = :nextAttemptAt,
                claim_token = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND summary_status = 'SUMMARIZING' AND claim_token = :token
        """,
        nativeQuery = true,
    )
    fun markFailed(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("nextAttemptAt") nextAttemptAt: LocalDateTime,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE cve_event
            SET summary_status = 'PENDING', claim_token = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE summary_status = 'SUMMARIZING' AND updated_at < :olderThan
        """,
        nativeQuery = true,
    )
    fun resetStuck(
        @Param("olderThan") olderThan: LocalDateTime,
    ): Int

    @Query("SELECT COUNT(e) FROM cve_event e WHERE e.summaryStatus = :status")
    fun countByStatus(
        @Param("status") status: CveSummaryStatus,
    ): Long

    @Query(
        """
        SELECT COUNT(e) FROM cve_event e
        WHERE e.summaryStatus = dev.notypie.repository.cve.schema.CveSummaryStatus.FAILED
          AND e.retryCount < :maxRetries
        """,
    )
    fun countFailedRetryable(
        @Param("maxRetries") maxRetries: Int,
    ): Long

    @Query(
        """
        SELECT COUNT(e) FROM cve_event e
        WHERE e.summaryStatus = dev.notypie.repository.cve.schema.CveSummaryStatus.FAILED
          AND e.retryCount >= :maxRetries
        """,
    )
    fun countDeadLetter(
        @Param("maxRetries") maxRetries: Int,
    ): Long

    @Query(
        """
        SELECT new dev.notypie.repository.cve.TopicEventCount(e.topicId, COUNT(e))
        FROM cve_event e
        WHERE e.topicId IN :topicIds
        GROUP BY e.topicId
        """,
    )
    fun countEventsByTopic(
        @Param("topicIds") topicIds: List<Long>,
    ): List<TopicEventCount>

    // Entity join on the unrelated cve_topic (topicId is a plain column, not a mapped relation); it
    // renders to a plain SQL join, so this runs on H2. Mirrors JpaCveDeliveryRepository.findUndelivered.
    @Query(
        """
        SELECT new dev.notypie.repository.cve.CveRecentEvent(t.displayName, e.title, e.aiSummary)
        FROM cve_event e
        JOIN cve_topic t ON t.id = e.topicId
        WHERE e.topicId IN :topicIds
          AND e.summaryStatus = dev.notypie.repository.cve.schema.CveSummaryStatus.DONE
        ORDER BY e.id DESC
        """,
    )
    fun findRecentDoneEvents(
        @Param("topicIds") topicIds: List<Long>,
        pageable: Pageable,
    ): List<CveRecentEvent>

    // Guarded so only true dead-letters (FAILED and out of budget) reset; a live retryable FAILED row
    // or a SUMMARIZING/DONE row is left untouched. updated_at is intentionally NOT stamped here
    // (unlike the CAS updates above): nothing reads it on PENDING rows — resetStuck only reads
    // SUMMARIZING and the digest cutoff only reads DONE, and the next claim re-stamps it anyway.
    @Modifying
    @Transactional
    @Query(
        """
        UPDATE cve_event e
        SET e.summaryStatus = dev.notypie.repository.cve.schema.CveSummaryStatus.PENDING,
            e.retryCount = 0, e.nextAttemptAt = NULL, e.claimToken = NULL
        WHERE e.summaryStatus = dev.notypie.repository.cve.schema.CveSummaryStatus.FAILED
          AND e.retryCount >= :maxRetries
        """,
    )
    fun resetDeadLetters(
        @Param("maxRetries") maxRetries: Int,
    ): Int

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE cve_event e
        SET e.summaryStatus = dev.notypie.repository.cve.schema.CveSummaryStatus.PENDING,
            e.retryCount = 0, e.nextAttemptAt = NULL, e.claimToken = NULL
        WHERE e.id = :id
          AND e.summaryStatus = dev.notypie.repository.cve.schema.CveSummaryStatus.FAILED
          AND e.retryCount >= :maxRetries
        """,
    )
    fun resetDeadLetter(
        @Param("id") id: Long,
        @Param("maxRetries") maxRetries: Int,
    ): Int
}
