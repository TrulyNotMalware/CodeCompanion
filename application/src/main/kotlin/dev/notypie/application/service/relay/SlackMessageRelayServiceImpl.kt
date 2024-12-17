package dev.notypie.application.service.relay

import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.dto.MessagePublishFailedEvent
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.outbox.dto.NewMessagePublishedEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val logger = KotlinLogging.logger {}

@Service
class SlackMessageRelayServiceImpl(
    private val outboxRepository: MessageOutboxRepository,
    private val applicationEventPublisher: ApplicationEventPublisher
): MessageRelayService {

    override fun dispatchPendingMessages() {
        
    }

    @Transactional
    @Retryable(
        maxAttempts = 3
    )
    @EventListener
    fun saveOutboxMessages(event: NewMessagePublishedEvent): OutboxMessage{
        logger.debug { "Save Outbox Message from ${event.reason}" }
        return this.outboxRepository.save(event.outboxMessage)
    }

    @Transactional
    @Retryable(
        maxAttempts = 3,
        backoff = Backoff(delay = 1000),
        retryFor = [ObjectOptimisticLockingFailureException::class]
    )
    @EventListener
    fun updateSuccessfulMessage(event: MessagePublishSuccessEvent){
        logger.debug { "Published Successfully. Update Outbox Message ${event.idempotencyKey}" }
        val pendingMessage = this.outboxRepository.findById(event.idempotencyKey)
            .orElseThrow { throw RuntimeException("Message Not Found.") }
        this.outboxRepository.save(
            pendingMessage.updateMessageStatus(status = MessageStatus.SUCCESS)
        )
    }

    @Transactional
    @Retryable(
        maxAttempts = 3,
        backoff = Backoff(delay = 1000),
        retryFor = [ObjectOptimisticLockingFailureException::class]
    )
    @EventListener
    fun updateFailedMessage(event: MessagePublishFailedEvent){
        logger.debug { "Published Failed. Update Outbox Message ${event.idempotencyKey}" }
        val pendingMessage = this.outboxRepository.findById(event.idempotencyKey)
            .orElseThrow { throw RuntimeException("Message Not Found.") }
        this.outboxRepository.save(
            pendingMessage.updateMessageStatus(status = MessageStatus.FAILURE)
        )
    }
}