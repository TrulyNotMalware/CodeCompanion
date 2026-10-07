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
import java.time.LocalDateTime

@Repository
interface JpaMeetingReminderRepository : JpaRepository<MeetingReminderSchema, Long> {
    fun findByMeetingIdAndOffsetMinutes(meetingId: Long, offsetMinutes: Int): MeetingReminderSchema?

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
              AND EXISTS (
                  SELECT 1 FROM meetings m WHERE m.id = meeting_reminder.meeting_id AND m.is_canceled = FALSE
              )
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
              AND EXISTS (
                  SELECT 1 FROM meetings m WHERE m.id = meeting_reminder.meeting_id AND m.is_canceled = FALSE
              )
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
        value = """
            UPDATE meeting_reminder
            SET scheduled_at = :scheduledAt, updated_at = :now
            WHERE id = :id AND status = 'PENDING' AND scheduled_at = :observedAt
              AND EXISTS (
                  SELECT 1 FROM meetings m WHERE m.id = meeting_reminder.meeting_id AND m.start_at = :startAt
              )
        """,
        nativeQuery = true,
    )
    fun realignPending(
        @Param("id") id: Long,
        @Param("observedAt") observedAt: Instant,
        @Param("scheduledAt") scheduledAt: Instant,
        @Param("startAt") startAt: LocalDateTime,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            DELETE FROM meeting_reminder
            WHERE id = :id AND status = 'PENDING' AND scheduled_at = :observedAt
        """,
        nativeQuery = true,
    )
    fun discardPending(
        @Param("id") id: Long,
        @Param("observedAt") observedAt: Instant,
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
