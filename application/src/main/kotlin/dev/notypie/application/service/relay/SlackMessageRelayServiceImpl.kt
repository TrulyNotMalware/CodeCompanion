package dev.notypie.application.service.relay

import dev.notypie.impl.command.SlackMessageDispatcher
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.SlackPostRequestMessage
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

class SlackMessageRelayServiceImpl(
    private val messageDispatcher: SlackMessageDispatcher,
    private val outboxRepository: MessageOutboxRepository
): MessageRelayService {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun relayMessage(slackPostRequestMessage: SlackPostRequestMessage){
        this.messageDispatcher.dispatch(slackPostRequestMessage = slackPostRequestMessage)
    }

    override fun dispatchPendingMessages() {

    }
}