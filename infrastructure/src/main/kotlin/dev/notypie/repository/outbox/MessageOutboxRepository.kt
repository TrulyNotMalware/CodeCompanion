package dev.notypie.repository.outbox

import dev.notypie.repository.outbox.schema.OutboxMessage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
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
}
