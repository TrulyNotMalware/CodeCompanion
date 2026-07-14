package dev.notypie.repository.cve

import dev.notypie.repository.cve.schema.CveDeliveryMode
import dev.notypie.repository.cve.schema.CveDeliverySchema
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Repository
interface JpaCveDeliveryRepository : JpaRepository<CveDeliverySchema, Long> {
    /**
     * Atomically claims delivery of [eventId] to [userId]. `INSERT IGNORE` swallows the
     * duplicate-key error on unique(event_id, user_id), so the affected-row count (1 = claimed,
     * 0 = already delivered) is the claim signal — no check-then-act race. `INSERT IGNORE` is
     * MariaDB-specific and cannot be exercised on H2, so the impl carries a delegation unit test.
     * The dispatcher issues this inside the same transaction as the outbox save, so a rolled-back
     * send undoes the claim too.
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT IGNORE INTO cve_delivery (event_id, user_id, status, created_at)
            VALUES (:eventId, :userId, 'SENT', CURRENT_TIMESTAMP(6))
        """,
        nativeQuery = true,
    )
    fun claim(
        @Param("eventId") eventId: Long,
        @Param("userId") userId: String,
    ): Int

    // Joins cve_topic and cve_subscription to cve_event by unrelated-entity ON, then anti-joins the
    // delivery ledger (d.id IS NULL) to keep only undelivered pairs. createdAt >= :since bounds the
    // scan to the delivery horizon (cve_event has no TTL); createdAt is DB-clock stamped, so callers
    // must derive :since from [dbNow] — an app-clock value would skew the horizon by the app/DB zone
    // gap (up to ~9h here: DB UTC, app JVM KST). updatedAt < :doneBefore is the visibility cutoff and
    // stays app-clock (markDone stamps updatedAt from the app clock when the summary lands).
    // Hibernate entity joins render to plain SQL joins, so this runs on H2.
    @Query(
        """
        SELECT new dev.notypie.repository.cve.UndeliveredCveEvent(
            e.id, s.userId, t.topicKey, t.displayName, e.title, e.aiSummary
        )
        FROM cve_event e
        JOIN cve_topic t ON t.id = e.topicId
        JOIN cve_subscription s ON s.topicId = e.topicId
        LEFT JOIN cve_delivery d ON d.eventId = e.id AND d.userId = s.userId
        WHERE e.summaryStatus = dev.notypie.repository.cve.schema.CveSummaryStatus.DONE
          AND t.deliveryMode = :deliveryMode
          AND t.active = true
          AND e.createdAt >= :since
          AND e.updatedAt < :doneBefore
          AND d.id IS NULL
        ORDER BY e.id ASC, s.userId ASC
        """,
    )
    fun findUndelivered(
        @Param("deliveryMode") deliveryMode: CveDeliveryMode,
        @Param("since") since: LocalDateTime,
        @Param("doneBefore") doneBefore: LocalDateTime,
        pageable: Pageable,
    ): List<UndeliveredCveEvent>

    // LOCALTIMESTAMP matches what CURRENT_TIMESTAMP(6) stamps into created_at on MariaDB (both are
    // the session-zone wall clock) and, unlike CURRENT_TIMESTAMP, is zone-less on H2 too.
    @Query(value = "SELECT LOCALTIMESTAMP(6)", nativeQuery = true)
    fun dbNow(): LocalDateTime
}
