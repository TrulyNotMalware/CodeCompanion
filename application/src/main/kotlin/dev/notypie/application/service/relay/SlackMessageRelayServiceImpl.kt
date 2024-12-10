package dev.notypie.application.service.relay

import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.repository.outbox.MessageOutboxRepository

class SlackMessageRelayServiceImpl(
    private val messageDispatcher: MessageDispatcher,
    private val outboxRepository: MessageOutboxRepository
): MessageRelayService {

    override fun dispatchPendingMessages() {
        
    }
}