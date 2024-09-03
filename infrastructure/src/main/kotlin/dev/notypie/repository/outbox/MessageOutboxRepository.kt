package dev.notypie.repository.outbox

import dev.notypie.repository.outbox.schema.MessageOutbox
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MessageOutboxRepository : JpaRepository<MessageOutbox, String>{
}