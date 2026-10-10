package dev.notypie.repository.calendar

import dev.notypie.repository.calendar.schema.MeetingCalendarEventSchema
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
interface JpaMeetingCalendarEventRepository : JpaRepository<MeetingCalendarEventSchema, Long> {
    // updated_at is assigned first so it reads the old status: MariaDB evaluates the assignments left to right
    // with the new values. created_at / updated_at are the JVM-zone wall time, as @CreationTimestamp writes them.
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO meeting_calendar_event
                (meeting_id, slack_user_id, status, change_seq, attempts, next_attempt_at, created_at, updated_at)
            VALUES (:meetingId, :slackUserId, 'PENDING', 1, 0, :now, :createdAt, :createdAt)
            ON DUPLICATE KEY UPDATE
                updated_at = CASE WHEN status = 'SYNCING' THEN updated_at ELSE :createdAt END,
                change_seq = change_seq + 1,
                status = CASE WHEN status = 'SYNCING' THEN status ELSE 'PENDING' END,
                attempts = 0,
                next_attempt_at = :now
        """,
        nativeQuery = true,
    )
    fun upsertDirty(
        @Param("meetingId") meetingId: Long,
        @Param("slackUserId") slackUserId: String,
        @Param("now") now: Instant,
        @Param("createdAt") createdAt: LocalDateTime,
    ): Int

    @Query(
        """
        SELECT e.id FROM meeting_calendar_event e
        WHERE e.status = dev.notypie.repository.calendar.schema.CalendarSyncStatus.PENDING
          AND e.nextAttemptAt <= :now
        ORDER BY e.nextAttemptAt ASC, e.id ASC
        """,
    )
    fun findDueIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<Long>

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_calendar_event
            SET status = 'SYNCING', claim_token = :token, updated_at = :now
            WHERE id = :id AND status = 'PENDING' AND next_attempt_at <= :now
        """,
        nativeQuery = true,
    )
    fun claim(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_calendar_event
            SET status = CASE WHEN change_seq = :observedSeq THEN 'SYNCED' ELSE 'PENDING' END,
                google_event_id = :googleEventId,
                attempts = 0,
                last_error = NULL,
                claim_token = NULL,
                next_attempt_at = :now,
                updated_at = :now
            WHERE id = :id AND status = 'SYNCING' AND claim_token = :token
        """,
        nativeQuery = true,
    )
    fun markSynced(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("observedSeq") observedSeq: Long,
        @Param("googleEventId") googleEventId: String,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            DELETE FROM meeting_calendar_event
            WHERE id = :id AND status = 'SYNCING' AND claim_token = :token AND change_seq = :observedSeq
        """,
        nativeQuery = true,
    )
    fun deleteSynced(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("observedSeq") observedSeq: Long,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_calendar_event
            SET status = 'PENDING',
                google_event_id = NULL,
                claim_token = NULL,
                next_attempt_at = :now,
                updated_at = :now
            WHERE id = :id AND status = 'SYNCING' AND claim_token = :token
        """,
        nativeQuery = true,
    )
    fun releaseWithoutEvent(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_calendar_event
            SET status = 'PENDING',
                attempts = CASE WHEN change_seq = :observedSeq THEN :attempts ELSE 0 END,
                next_attempt_at = CASE WHEN change_seq = :observedSeq THEN :nextAttemptAt ELSE :now END,
                last_error = :lastError,
                claim_token = NULL,
                updated_at = :now
            WHERE id = :id AND status = 'SYNCING' AND claim_token = :token
        """,
        nativeQuery = true,
    )
    fun retryLater(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("observedSeq") observedSeq: Long,
        @Param("attempts") attempts: Int,
        @Param("nextAttemptAt") nextAttemptAt: Instant,
        @Param("lastError") lastError: String,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_calendar_event
            SET status = CASE WHEN change_seq = :observedSeq THEN 'FAILED' ELSE 'PENDING' END,
                attempts = CASE WHEN change_seq = :observedSeq THEN :attempts ELSE 0 END,
                next_attempt_at = :now,
                last_error = :lastError,
                claim_token = NULL,
                updated_at = :now
            WHERE id = :id AND status = 'SYNCING' AND claim_token = :token
        """,
        nativeQuery = true,
    )
    fun markFailed(
        @Param("id") id: Long,
        @Param("token") token: String,
        @Param("observedSeq") observedSeq: Long,
        @Param("attempts") attempts: Int,
        @Param("lastError") lastError: String,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_calendar_event
            SET status = 'PENDING', claim_token = NULL, updated_at = :now
            WHERE status = 'SYNCING' AND updated_at < :olderThan
        """,
        nativeQuery = true,
    )
    fun resetStuckSyncing(
        @Param("olderThan") olderThan: Instant,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_calendar_event
            SET updated_at = CASE WHEN status = 'SYNCING' THEN updated_at ELSE :now END,
                change_seq = change_seq + 1,
                status = CASE WHEN status = 'SYNCING' THEN status ELSE 'PENDING' END,
                attempts = 0,
                next_attempt_at = :now
            WHERE meeting_id = :meetingId
        """,
        nativeQuery = true,
    )
    fun touchByMeetingId(
        @Param("meetingId") meetingId: Long,
        @Param("now") now: Instant,
    ): Int

    // Bound as its 36-character text: a native query has no mapped attribute to derive the UUID binding from,
    // and V2 declares meetings.meeting_uid CHAR(36).
    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_calendar_event
            SET updated_at = CASE WHEN status = 'SYNCING' THEN updated_at ELSE :now END,
                change_seq = change_seq + 1,
                status = CASE WHEN status = 'SYNCING' THEN status ELSE 'PENDING' END,
                attempts = 0,
                next_attempt_at = :now
            WHERE meeting_id IN (SELECT m.id FROM meetings m WHERE m.meeting_uid = :meetingUid)
        """,
        nativeQuery = true,
    )
    fun touchByMeetingUid(
        @Param("meetingUid") meetingUid: String,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_calendar_event
            SET updated_at = CASE WHEN status = 'SYNCING' THEN updated_at ELSE :now END,
                change_seq = change_seq + 1,
                status = CASE WHEN status = 'SYNCING' THEN status ELSE 'PENDING' END,
                attempts = 0,
                next_attempt_at = :now
            WHERE meeting_id = :meetingId AND slack_user_id = :slackUserId
        """,
        nativeQuery = true,
    )
    fun touchOne(
        @Param("meetingId") meetingId: Long,
        @Param("slackUserId") slackUserId: String,
        @Param("now") now: Instant,
    ): Int

    // An explicit id list instead of a meetings subselect: under REPEATABLE READ the subselect is a locking read
    // that holds shared locks on every meetings row it scans until the connect transaction commits.
    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_calendar_event
            SET updated_at = CASE WHEN status = 'SYNCING' THEN updated_at ELSE :now END,
                change_seq = change_seq + 1,
                status = CASE WHEN status = 'SYNCING' THEN status ELSE 'PENDING' END,
                attempts = 0,
                next_attempt_at = :now
            WHERE slack_user_id = :slackUserId AND meeting_id IN (:meetingIds)
        """,
        nativeQuery = true,
    )
    fun touchBySlackUserId(
        @Param("slackUserId") slackUserId: String,
        @Param("meetingIds") meetingIds: Collection<Long>,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Transactional
    @Query(
        value = """
            UPDATE meeting_calendar_event
            SET status = 'FAILED', last_error = :lastError, claim_token = NULL, updated_at = :now
            WHERE slack_user_id = :slackUserId AND status = 'PENDING'
        """,
        nativeQuery = true,
    )
    fun failAllPendingForUser(
        @Param("slackUserId") slackUserId: String,
        @Param("lastError") lastError: String,
        @Param("now") now: Instant,
    ): Int
}
