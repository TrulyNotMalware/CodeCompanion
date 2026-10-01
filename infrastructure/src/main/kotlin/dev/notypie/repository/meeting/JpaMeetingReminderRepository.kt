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

    // A collection fetch plus a Pageable does not page in memory here: Hibernate 7.4 applies the LIMIT to the reminder
    // rows in a derived table and joins the participants outside it (MeetingReminderRepositoryTest runs with
    // fail_on_pagination_over_collection_fetch on), so neither DISTINCT nor an ids-first query is needed. LEFT like
    // every other meeting read; a meeting without participant rows never gets a reminder materialized anyway.
    @Query(
        """
        SELECT r FROM meeting_reminder r
        JOIN FETCH r.meeting m
        LEFT JOIN FETCH m.participants
        WHERE r.status = dev.notypie.domain.meet.entity.enums.MeetingReminderStatus.PENDING
          AND r.scheduledAt <= :before
          AND m.isCanceled = false
        ORDER BY r.scheduledAt ASC
        """,
    )
    fun findPendingBefore(
        @Param("before") before: Instant,
        pageable: Pageable,
    ): List<MeetingReminderSchema>

    // Atomic UPDATE guarded by status = 'PENDING' — a derived find-then-save here would race and double-dispatch.
    // Claim and markSent both re-check that the meeting is still active: the due read filters canceled meetings,
    // but a cancel can commit after that read, and only markSent runs inside the outbox transaction (review M7).
    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_reminder
            SET status = 'SENDING', claim_token = :token, updated_at = CURRENT_TIMESTAMP
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
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_reminder
            SET status = 'SENT', sent_at = :sentAt, claim_token = NULL, updated_at = CURRENT_TIMESTAMP
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
            SET status = 'FAILED', failure_reason = :reason, claim_token = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'SENDING' AND claim_token = :token
        """,
        nativeQuery = true,
    )
    fun markFailed(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("reason") reason: String,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_reminder
            SET status = 'PENDING', claim_token = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE status = 'SENDING' AND updated_at < :olderThan
        """,
        nativeQuery = true,
    )
    fun resetStuckSending(
        @Param("olderThan") olderThan: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_reminder
            SET scheduled_at = :scheduledAt, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'PENDING'
        """,
        nativeQuery = true,
    )
    fun realignPending(
        @Param("id") id: Long,
        @Param("scheduledAt") scheduledAt: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = "DELETE FROM meeting_reminder WHERE id = :id AND status = 'PENDING'",
        nativeQuery = true,
    )
    fun discardPending(
        @Param("id") id: Long,
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
