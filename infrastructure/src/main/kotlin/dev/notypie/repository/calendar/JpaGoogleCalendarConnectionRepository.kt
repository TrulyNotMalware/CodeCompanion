package dev.notypie.repository.calendar

import dev.notypie.repository.calendar.schema.CalendarConnectionStatus
import dev.notypie.repository.calendar.schema.GoogleCalendarConnectionSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime

@Repository
interface JpaGoogleCalendarConnectionRepository : JpaRepository<GoogleCalendarConnectionSchema, Long> {
    fun findBySlackUserId(slackUserId: String): GoogleCalendarConnectionSchema?

    fun deleteBySlackUserId(slackUserId: String): Long

    fun existsBySlackUserIdAndStatus(slackUserId: String, status: CalendarConnectionStatus): Boolean

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE google_calendar_connection c
        SET c.status = dev.notypie.repository.calendar.schema.CalendarConnectionStatus.REVOKED,
            c.revokedAt = :now,
            c.lastError = :reason,
            c.updatedAt = :updatedAt
        WHERE c.slackUserId = :userId
          AND c.status = dev.notypie.repository.calendar.schema.CalendarConnectionStatus.ACTIVE
          AND c.encryptedRefreshToken = :observedEncryptedRefreshToken
        """,
    )
    fun markRevoked(
        @Param("userId") userId: String,
        @Param("observedEncryptedRefreshToken") observedEncryptedRefreshToken: String,
        @Param("now") now: Instant,
        @Param("updatedAt") updatedAt: LocalDateTime,
        @Param("reason") reason: String,
    ): Int
}
