package dev.notypie.repository.outbox

import dev.notypie.repository.outbox.schema.OutboxMessage
import jakarta.persistence.QueryHint
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

// Health and Prometheus read these on every probe or scrape; a stuck database must not hang the endpoint.
const val HEALTH_QUERY_TIMEOUT_HINT = "jakarta.persistence.query.timeout"
const val HEALTH_QUERY_TIMEOUT_MILLIS = "2000"

@Repository
interface MessageOutboxRepository : JpaRepository<OutboxMessage, String> {
    @Query(
        """
        SELECT * FROM outbox_message
        WHERE status = 'PENDING'
        ORDER BY created_at ASC
        LIMIT :limit
    """,
        nativeQuery = true,
    )
    fun findPendingMessages(
        @Param("limit") limit: Int,
    ): List<OutboxMessage>

    // Atomic UPDATE guarded by status = 'PENDING' — a derived find-then-save here would race and double-dispatch.
    @Modifying
    @Transactional
    @Query(
        """
        UPDATE outbox_message
        SET status = 'IN_PROGRESS', attempt_count = attempt_count + 1, updated_at = :now
        WHERE event_id = :eventId
          AND status = 'PENDING'
          AND attempt_count = :attemptCount
    """,
        nativeQuery = true,
    )
    fun claimPending(
        @Param("eventId") eventId: String,
        @Param("attemptCount") attemptCount: Int,
        @Param("now") now: LocalDateTime,
    ): Int

    @Query(
        """
        SELECT * FROM outbox_message
        WHERE status = 'IN_PROGRESS' AND updated_at < :olderThan
        ORDER BY updated_at ASC
        LIMIT :limit
    """,
        nativeQuery = true,
    )
    fun findStuckInProgress(
        @Param("olderThan") olderThan: LocalDateTime,
        @Param("limit") limit: Int,
    ): List<OutboxMessage>

    @Query(
        """
        SELECT * FROM outbox_message
        WHERE status = 'PENDING' AND created_at < :olderThan
        ORDER BY created_at ASC
        LIMIT :limit
    """,
        nativeQuery = true,
    )
    fun findStalePending(
        @Param("olderThan") olderThan: LocalDateTime,
        @Param("limit") limit: Int,
    ): List<OutboxMessage>

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE outbox_message
        SET attempt_count = attempt_count + 1, updated_at = :now
        WHERE event_id = :eventId
          AND status = 'IN_PROGRESS'
          AND attempt_count = :attemptCount
          AND updated_at < :olderThan
    """,
        nativeQuery = true,
    )
    fun reclaimStuck(
        @Param("eventId") eventId: String,
        @Param("attemptCount") attemptCount: Int,
        @Param("olderThan") olderThan: LocalDateTime,
        @Param("now") now: LocalDateTime,
    ): Int

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE outbox_message
        SET status = 'FAILURE', updated_at = :now
        WHERE event_id = :eventId
          AND status = 'IN_PROGRESS'
          AND attempt_count = :attemptCount
          AND updated_at < :olderThan
    """,
        nativeQuery = true,
    )
    fun abandonStuck(
        @Param("eventId") eventId: String,
        @Param("attemptCount") attemptCount: Int,
        @Param("olderThan") olderThan: LocalDateTime,
        @Param("now") now: LocalDateTime,
    ): Int

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE outbox_message
        SET send_count = send_count + 1, updated_at = :now
        WHERE event_id = :eventId
          AND status = 'IN_PROGRESS'
          AND attempt_count = :attemptCount
    """,
        nativeQuery = true,
    )
    fun renewClaim(
        @Param("eventId") eventId: String,
        @Param("attemptCount") attemptCount: Int,
        @Param("now") now: LocalDateTime,
    ): Int

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE outbox_message
        SET send_count = GREATEST(send_count - 1, 0), updated_at = :updatedAt
        WHERE event_id = :eventId
          AND status = 'IN_PROGRESS'
          AND attempt_count = :attemptCount
    """,
        nativeQuery = true,
    )
    fun deferClaim(
        @Param("eventId") eventId: String,
        @Param("attemptCount") attemptCount: Int,
        @Param("updatedAt") updatedAt: LocalDateTime,
    ): Int

    @Modifying
    @Transactional
    @Query(
        """
        UPDATE outbox_message
        SET status = :status, updated_at = :now
        WHERE event_id = :eventId
          AND status = 'IN_PROGRESS'
          AND attempt_count = :attemptCount
    """,
        nativeQuery = true,
    )
    fun completeClaim(
        @Param("eventId") eventId: String,
        @Param("attemptCount") attemptCount: Int,
        @Param("status") status: String,
        @Param("now") now: LocalDateTime,
    ): Int

    // Retention: terminal rows only, and LIMIT keeps one purge from holding a long lock on a large backlog.
    @Modifying
    @Transactional
    @Query(
        """
        DELETE FROM outbox_message
        WHERE status IN ('SUCCESS', 'FAILURE')
          AND updated_at < :olderThan
        LIMIT :limit
    """,
        nativeQuery = true,
    )
    fun deleteTerminalOlderThan(
        @Param("olderThan") olderThan: LocalDateTime,
        @Param("limit") limit: Int,
    ): Int

    @QueryHints(QueryHint(name = HEALTH_QUERY_TIMEOUT_HINT, value = HEALTH_QUERY_TIMEOUT_MILLIS))
    @Query(
        """
        SELECT MIN(created_at) FROM outbox_message
        WHERE status = 'PENDING'
    """,
        nativeQuery = true,
    )
    fun findOldestPendingCreatedAt(): LocalDateTime?

    @QueryHints(QueryHint(name = HEALTH_QUERY_TIMEOUT_HINT, value = HEALTH_QUERY_TIMEOUT_MILLIS))
    @Query(
        """
        SELECT COUNT(*) FROM outbox_message
        WHERE status = 'PENDING'
    """,
        nativeQuery = true,
    )
    fun countPending(): Long

    @QueryHints(QueryHint(name = HEALTH_QUERY_TIMEOUT_HINT, value = HEALTH_QUERY_TIMEOUT_MILLIS))
    @Query(
        """
        SELECT COUNT(*) FROM outbox_message
        WHERE status = 'PENDING' AND created_at < :threshold
    """,
        nativeQuery = true,
    )
    fun countPendingOlderThan(
        @Param("threshold") threshold: LocalDateTime,
    ): Long

    @QueryHints(QueryHint(name = HEALTH_QUERY_TIMEOUT_HINT, value = HEALTH_QUERY_TIMEOUT_MILLIS))
    @Query(
        """
        SELECT COUNT(*) FROM outbox_message
        WHERE status = 'IN_PROGRESS'
    """,
        nativeQuery = true,
    )
    fun countInProgress(): Long

    @QueryHints(QueryHint(name = HEALTH_QUERY_TIMEOUT_HINT, value = HEALTH_QUERY_TIMEOUT_MILLIS))
    @Query(
        """
        SELECT COUNT(*) FROM outbox_message
        WHERE status = 'IN_PROGRESS' AND updated_at < :threshold
    """,
        nativeQuery = true,
    )
    fun countInProgressOlderThan(
        @Param("threshold") threshold: LocalDateTime,
    ): Long

    @QueryHints(QueryHint(name = HEALTH_QUERY_TIMEOUT_HINT, value = HEALTH_QUERY_TIMEOUT_MILLIS))
    @Query(
        """
        SELECT MIN(updated_at) FROM outbox_message
        WHERE status = 'IN_PROGRESS'
    """,
        nativeQuery = true,
    )
    fun findOldestInProgressUpdatedAt(): LocalDateTime?

    @QueryHints(QueryHint(name = HEALTH_QUERY_TIMEOUT_HINT, value = HEALTH_QUERY_TIMEOUT_MILLIS))
    @Query(
        """
        SELECT COUNT(*) FROM outbox_message
        WHERE status = 'IN_PROGRESS' AND send_count >= :sends
    """,
        nativeQuery = true,
    )
    fun countInProgressWithSendsAtLeast(
        @Param("sends") sends: Int,
    ): Long
}
