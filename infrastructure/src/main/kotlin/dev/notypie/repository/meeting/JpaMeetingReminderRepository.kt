package dev.notypie.repository.meeting

import dev.notypie.repository.meeting.schema.MeetingReminderSchema
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
interface JpaMeetingReminderRepository : JpaRepository<MeetingReminderSchema, Long> {
    fun findByMeetingIdAndOffsetMinutes(meetingId: Long, offsetMinutes: Int): MeetingReminderSchema?

    // Ids first: paging a query that fetches the participants collection would page in memory (HHH90003004).
    @Query(
        """
        SELECT r.id FROM meeting_reminder r
        JOIN r.meeting m
        WHERE r.status = dev.notypie.domain.meet.entity.enums.MeetingReminderStatus.PENDING
          AND r.scheduledAt <= :before
          AND m.isCanceled = false
        ORDER BY r.scheduledAt ASC
        """,
    )
    fun findPendingIdsBefore(
        @Param("before") before: Instant,
        pageable: Pageable,
    ): List<Long>

    // LEFT: a reminder whose meeting has no participant rows must still come back and be closed out; an inner
    // fetch would drop it while findPendingIdsBefore keeps returning its id, holding a page slot forever.
    @Query(
        """
        SELECT r FROM meeting_reminder r
        JOIN FETCH r.meeting m
        LEFT JOIN FETCH m.participants
        WHERE r.id IN :ids
        ORDER BY r.scheduledAt ASC
        """,
    )
    fun findWithMeetingAndParticipantsByIdIn(
        @Param("ids") ids: Collection<Long>,
    ): List<MeetingReminderSchema>

    // Atomic UPDATE guarded by status = 'PENDING' — a derived find-then-save here would race and double-dispatch.
    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_reminder
            SET status = 'SENDING', claim_token = :token, updated_at = :now
            WHERE id = :id AND status = 'PENDING'
        """,
        nativeQuery = true,
    )
    fun claimReminder(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_reminder
            SET status = 'SENT', sent_at = :sentAt, claim_token = NULL, updated_at = :sentAt
            WHERE id = :id AND status = 'SENDING' AND claim_token = :token
        """,
        nativeQuery = true,
    )
    fun markSent(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("sentAt") sentAt: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_reminder
            SET status = 'FAILED', failure_reason = :reason, claim_token = NULL, updated_at = :now
            WHERE id = :id AND status = 'SENDING' AND claim_token = :token
        """,
        nativeQuery = true,
    )
    fun markFailed(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("reason") reason: String,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_reminder
            SET status = 'PENDING', claim_token = NULL, updated_at = :now
            WHERE status = 'SENDING' AND updated_at < :olderThan
        """,
        nativeQuery = true,
    )
    fun resetStuckSending(
        @Param("olderThan") olderThan: Instant,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = "DELETE FROM meeting_reminder WHERE meeting_id = :meetingId",
        nativeQuery = true,
    )
    fun deleteByMeetingId(
        @Param("meetingId") meetingId: Long,
    ): Int
}
