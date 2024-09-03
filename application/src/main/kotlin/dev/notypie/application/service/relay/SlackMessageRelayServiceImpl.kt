package dev.notypie.application.service.relay

import dev.notypie.impl.command.SlackMessageDispatcher
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.SlackPostRequestMessage
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

class SlackMessageRelayServiceImpl(
    private val messageOutboxRepository: MessageOutboxRepository,
    private val messageDispatcher: SlackMessageDispatcher
): MessageRelayService {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun relayMessage(message: SlackPostRequestMessage){

    }


    private fun dispatch(){

    }
}