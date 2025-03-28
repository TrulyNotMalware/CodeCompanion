package dev.notypie.repository.outbox

import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.outbox.schema.MessageStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface MessageOutboxRepository : JpaRepository<OutboxMessage, String>{

    @Query("""
        SELECT * FROM outbox_message
        WHERE status = 'PENDING'
        ORDER BY created_at ASC
        LIMIT :limit OFFSET :offset
    """, nativeQuery = true)
    fun findPendingMessages(@Param("limit") limit: Int,
                            @Param("offset") offset: Int): List<OutboxMessage>
}