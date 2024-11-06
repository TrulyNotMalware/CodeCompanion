package dev.notypie.application.service.relay

import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.impl.command.ApplicationMessageDispatcher
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.SlackPostRequestMessage
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

class SlackMessageRelayServiceImpl(
    private val messageDispatcher: MessageDispatcher,
    private val outboxRepository: MessageOutboxRepository
): MessageRelayService {
    override fun dispatchPendingMessages() {

    }
}