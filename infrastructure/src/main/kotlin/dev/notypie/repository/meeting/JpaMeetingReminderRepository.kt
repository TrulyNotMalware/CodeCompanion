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

    // DISTINCT collapses the participant cartesian (JOIN FETCH on the to-many collection would
    // otherwise repeat each reminder row once per attendee). The claim CAS already makes a
    // duplicate harmless, but without DISTINCT those duplicates would also eat into the page limit.
    @Query(
        """
        SELECT DISTINCT r FROM meeting_reminder r
        JOIN FETCH r.meeting m
        JOIN FETCH m.participants
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

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_reminder
            SET status = 'SENDING', claim_token = :token, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND status = 'PENDING'
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
        value = "DELETE FROM meeting_reminder WHERE meeting_id = :meetingId",
        nativeQuery = true,
    )
    fun deleteByMeetingId(
        @Param("meetingId") meetingId: Long,
    ): Int
}
