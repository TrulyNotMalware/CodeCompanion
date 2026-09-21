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

    // Native bulk updates bypass Hibernate's @UpdateTimestamp — updated_at is stamped explicitly in every CAS below.
    // Re-checks retry_count here (not just in findClaimable) so a stale candidate can't revive a dead-lettered row.
    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE cve_event
            SET summary_status = 'SUMMARIZING', claim_token = :token, updated_at = :now
            WHERE id = :id AND summary_status IN ('PENDING', 'FAILED')
              AND retry_count < :maxRetries
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
        """,
        nativeQuery = true,
    )
    fun claimForSummary(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("now") now: LocalDateTime,
        @Param("maxRetries") maxRetries: Int,
    ): Int

    // Unlike markFailed, this doesn't increment retry_count — a busy-sidecar release must not spend the retry budget.
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

    // updated_at is stamped from :now (app clock) because the notification dispatcher's digest cutoff compares it.
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

    // Loosening this guard (FAILED AND retryCount >= maxRetries) would revive a row still within its retry budget.
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
