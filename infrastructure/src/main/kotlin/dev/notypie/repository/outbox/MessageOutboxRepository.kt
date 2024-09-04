package dev.notypie.repository.outbox

import dev.notypie.repository.outbox.schema.MessageOutbox
import dev.notypie.repository.outbox.schema.MessageStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface MessageOutboxRepository : JpaRepository<MessageOutbox, String>{

//    @Query("SELECT m FROM MessageOutbox m WHERE m.status = 'PENDING'")
    fun findByStatus(status: MessageStatus): List<MessageOutbox>
}