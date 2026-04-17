package dev.notypie.repository.meeting

import dev.notypie.repository.meeting.schema.MeetingSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface JpaMeetingRepository : JpaRepository<MeetingSchema, Long> {
    @Query(
        """
            SELECT m FROM meetings m
            JOIN FETCH m.participants
            WHERE m.id = :meetingId
        """,
    )
    fun findMeetingWithParticipants(
        @Param("meetingId") meetingId: Long,
    ): MeetingSchema?

    @Query(
        """
        SELECT DISTINCT m
        FROM meetings m
        JOIN FETCH m.participants p
        WHERE m.publisherId = :userId
        OR p.userId = :userId
    """,
    )
    fun findAllMeetingByUserId(userId: String): List<MeetingSchema>

    @Query(
        """
        SELECT DISTINCT m
        FROM meetings m
        JOIN FETCH m.participants p
        WHERE (m.publisherId = :userId OR p.userId = :userId)
          AND m.startAt >= :startAt
          AND m.startAt < :endAt
        ORDER BY m.startAt ASC
    """,
    )
    fun findMeetingsByUserIdAndDateRange(
        @Param("userId") userId: String,
        @Param("startAt") startAt: LocalDateTime,
        @Param("endAt") endAt: LocalDateTime,
    ): List<MeetingSchema>
}
