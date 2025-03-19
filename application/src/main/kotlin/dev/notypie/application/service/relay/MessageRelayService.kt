package dev.notypie.application.service.relay

import dev.notypie.repository.outbox.schema.OutboxMessage

interface MessageRelayService {
    fun batchPendingMessages(pendingMessages: List<OutboxMessage>)
}