package dev.notypie.repository.outbox

import dev.notypie.repository.outbox.schema.OutboxMessage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Repository
interface MessageOutboxRepository : JpaRepository<OutboxMessage, String> {
    @Query(
        """
        SELECT * FROM outbox_message
        WHERE status = 'PENDING'
        ORDER BY created_at ASC
        LIMIT :limit OFFSET :offset
    """,
        nativeQuery = true,
    )
    fun findPendingMessages(
        @Param("limit") limit: Int,
        @Param("offset") offset: Int,
    ): List<OutboxMessage>

    /**
     * Atomic batch claim for PENDING rows whose `event_id` is in [eventIds]. The status check
     * in the WHERE clause is the source of truth — only rows still PENDING transition to
     * IN_PROGRESS, so a row claimed by a racing poller will not be re-claimed here. Returns
     * the number of rows actually transitioned. Callers should treat the *intersection* of
     * the claimed-row count and the candidate list as the work to dispatch; an unclaimed
     * candidate was either racing or had already moved out of PENDING.
     *
     * `updated_at` is touched explicitly so health indicators can age IN_PROGRESS rows from
     * the moment of claim, not from the row's original creation time.
     */
    @Modifying
    @Transactional
    @Query(
        """
        UPDATE outbox_message
        SET status = 'IN_PROGRESS', updated_at = CURRENT_TIMESTAMP
        WHERE event_id IN (:eventIds)
          AND status = 'PENDING'
    """,
        nativeQuery = true,
    )
    fun claimPending(
        @Param("eventIds") eventIds: List<String>,
    ): Int

    /**
     * Returns rows currently IN_PROGRESS (claimed but not yet finalized). Used by the polling
     * loop to recover crash-orphaned claims older than [olderThan]: a row stuck in this state
     * means the dispatcher process died before publishing SUCCESS/FAILURE, and re-dispatching
     * is safer than leaving it pinned. The dispatch path is idempotent at Slack's side because
     * each row carries its own event_id.
     */
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
        SELECT MIN(created_at) FROM outbox_message
        WHERE status = 'PENDING'
    """,
        nativeQuery = true,
    )
    fun findOldestPendingCreatedAt(): LocalDateTime?

    @Query(
        """
        SELECT COUNT(*) FROM outbox_message
        WHERE status = 'PENDING'
    """,
        nativeQuery = true,
    )
    fun countPending(): Long

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

    @Query(
        """
        SELECT COUNT(*) FROM outbox_message
        WHERE status = 'IN_PROGRESS'
    """,
        nativeQuery = true,
    )
    fun countInProgress(): Long

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

    @Query(
        """
        SELECT MIN(updated_at) FROM outbox_message
        WHERE status = 'IN_PROGRESS'
    """,
        nativeQuery = true,
    )
    fun findOldestInProgressUpdatedAt(): LocalDateTime?
}
