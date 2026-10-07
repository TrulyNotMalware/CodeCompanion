package dev.notypie.repository.meeting

import dev.notypie.domain.meet.entity.RejectReason
import dev.notypie.repository.meeting.schema.MeetingSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Repository
interface JpaMeetingRepository : JpaRepository<MeetingSchema, Long> {
    @Query(
        """
            SELECT m FROM meetings m
            LEFT JOIN FETCH m.participants
            WHERE m.id = :meetingId
        """,
    )
    fun findMeetingWithParticipants(
        @Param("meetingId") meetingId: Long,
    ): MeetingSchema?

    @Query(
        """
        SELECT m
        FROM meetings m
        LEFT JOIN FETCH m.participants
        WHERE m.publisherId = :userId
           OR EXISTS (SELECT 1 FROM meeting_participants p WHERE p.meeting = m AND p.userId = :userId)
    """,
    )
    fun findAllMeetingByUserId(userId: String): List<MeetingSchema>

    @Query(
        """
        SELECT m
        FROM meetings m
        LEFT JOIN FETCH m.participants
        WHERE (m.publisherId = :userId
               OR EXISTS (SELECT 1 FROM meeting_participants p WHERE p.meeting = m AND p.userId = :userId))
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

    @Query(
        """
        SELECT m
        FROM meetings m
        LEFT JOIN FETCH m.participants
        WHERE m.isCanceled = false
          AND m.startAt >= :startAt
          AND m.startAt < :endAt
        ORDER BY m.startAt ASC
    """,
    )
    fun findActiveByStartAtBetween(
        @Param("startAt") startAt: LocalDateTime,
        @Param("endAt") endAt: LocalDateTime,
    ): List<MeetingSchema>

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE meeting_participants p
        SET p.isAttending = :isAttending,
            p.absentReason = :absentReason,
            p.absentReasonDetail = :absentReasonDetail
        WHERE p.userId = :userId
          AND p.meeting.idempotencyKey = :meetingIdempotencyKey
    """,
    )
    fun updateParticipantAttendance(
        @Param("meetingIdempotencyKey") meetingIdempotencyKey: UUID,
        @Param("userId") userId: String,
        @Param("isAttending") isAttending: Boolean,
        @Param("absentReason") absentReason: RejectReason,
        @Param("absentReasonDetail") absentReasonDetail: String?,
    ): Int

    @Query(
        """
        SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END
        FROM meeting_participants p
        WHERE p.userId = :userId
          AND p.meeting.idempotencyKey = :meetingIdempotencyKey
    """,
    )
    fun existsParticipant(
        @Param("meetingIdempotencyKey") meetingIdempotencyKey: UUID,
        @Param("userId") userId: String,
    ): Boolean

    @Query(
        """
            SELECT m FROM meetings m
            LEFT JOIN FETCH m.participants
            WHERE m.meetingUid = :meetingUid
        """,
    )
    fun findMeetingByUidWithParticipants(
        @Param("meetingUid") meetingUid: UUID,
    ): MeetingSchema?
}
