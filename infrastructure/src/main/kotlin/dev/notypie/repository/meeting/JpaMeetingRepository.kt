package dev.notypie.repository.meeting

import dev.notypie.domain.command.dto.interactions.RejectReason
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

    /**
     * Candidate meetings for reminder materialization: non-canceled meetings whose `startAt`
     * falls in the forward window, with participants eagerly fetched so the scheduler can size
     * the per-offset reminder rows without an extra round-trip. Not user-scoped — the reminder
     * scheduler sweeps across all meetings, unlike [findMeetingsByUserIdAndDateRange].
     */
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

    /**
     * Existence probe used to disambiguate `updateParticipantAttendance` returning 0:
     *  - row exists AND new values equal current ones → UPDATE is a no-op and still returns 0
     *    on MariaDB's default `CLIENT_FOUND_ROWS=false`; we must not treat this as missing data.
     *  - row doesn't exist → genuine routing failure; caller should throw to trigger rollback.
     */
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

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE meetings m
        SET m.startAt = :newStartAt
        WHERE m.meetingUid = :meetingUid
          AND m.publisherId = :requesterId
          AND m.isCanceled = false
    """,
    )
    fun rescheduleMeeting(
        @Param("meetingUid") meetingUid: UUID,
        @Param("requesterId") requesterId: String,
        @Param("newStartAt") newStartAt: LocalDateTime,
    ): Int

    @Query(
        """
            SELECT m FROM meetings m
            JOIN FETCH m.participants
            WHERE m.meetingUid = :meetingUid
        """,
    )
    fun findMeetingByUidWithParticipants(
        @Param("meetingUid") meetingUid: UUID,
    ): MeetingSchema?
}
