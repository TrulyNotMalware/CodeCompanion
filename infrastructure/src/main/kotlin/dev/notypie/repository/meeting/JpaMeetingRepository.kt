package dev.notypie.repository.meeting

import dev.notypie.domain.meet.entity.RejectReason
import dev.notypie.repository.meeting.schema.MeetingSchema
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
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
            JOIN FETCH m.participants
            WHERE m.id = :meetingId
        """,
    )
    fun findMeetingWithParticipants(
        @Param("meetingId") meetingId: Long,
    ): MeetingSchema?

    // The user filter lives in a subquery, never on the fetch-join alias: filtering the alias makes Hibernate
    // initialise `participants` with only the matching rows, so a participant would see themselves alone.
    @Query(
        """
        SELECT m
        FROM meetings m
        JOIN FETCH m.participants
        WHERE m.publisherId = :userId
           OR EXISTS (SELECT 1 FROM meeting_participants p WHERE p.meeting = m AND p.userId = :userId)
    """,
    )
    fun findAllMeetingByUserId(userId: String): List<MeetingSchema>

    @Query(
        """
        SELECT m
        FROM meetings m
        JOIN FETCH m.participants
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

    // Not user-scoped, unlike findMeetingsByUserIdAndDateRange — the reminder scheduler sweeps all meetings.
    @Query(
        """
        SELECT DISTINCT m
        FROM meetings m
        JOIN FETCH m.participants
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

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE meetings m
        SET m.isCanceled = true
        WHERE m.meetingUid = :meetingUid
          AND m.publisherId = :requesterId
          AND m.isCanceled = false
    """,
    )
    fun markMeetingCanceled(
        @Param("meetingUid") meetingUid: UUID,
        @Param("requesterId") requesterId: String,
    ): Int

    // The caller reads the row before this UPDATE; clearing keeps a later read in the same tx from seeing the old times.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        """
        UPDATE meetings m
        SET m.startAt = :newStartAt, m.endAt = :newEndAt
        WHERE m.meetingUid = :meetingUid
          AND m.publisherId = :requesterId
          AND m.isCanceled = false
    """,
    )
    fun rescheduleMeeting(
        @Param("meetingUid") meetingUid: UUID,
        @Param("requesterId") requesterId: String,
        @Param("newStartAt") newStartAt: LocalDateTime,
        @Param("newEndAt") newEndAt: LocalDateTime?,
    ): Int

    // Writes read through this lookup: the forced version bump makes two concurrent writers conflict at commit.
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("SELECT m FROM meetings m WHERE m.meetingUid = :meetingUid")
    fun findMeetingByUidForUpdate(
        @Param("meetingUid") meetingUid: UUID,
    ): MeetingSchema?

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
