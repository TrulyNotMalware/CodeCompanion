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
    // MariaDB-only INSERT IGNORE (untestable on H2) on unique(event_id,user_id) — the only guard against a double DM.
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

    // Must read the DB clock, not the app clock — DB is UTC, app JVM is KST; the delivery horizon depends on this.
    @Query(value = "SELECT LOCALTIMESTAMP(6)", nativeQuery = true)
    fun dbNow(): LocalDateTime
}
