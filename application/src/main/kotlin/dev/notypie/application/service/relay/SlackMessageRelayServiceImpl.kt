package dev.notypie.application.service.relay

import dev.notypie.repository.outbox.MessageOutboxRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

//@Service
class SlackMessageRelayServiceImpl(
    private val outboxRepository: MessageOutboxRepository,
    private val applicationEVentPublisher: ApplicationEventPublisher
): MessageRelayService {

    override fun dispatchPendingMessages() {
        
    }
}