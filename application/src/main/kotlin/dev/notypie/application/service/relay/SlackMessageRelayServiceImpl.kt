package dev.notypie.application.service.relay

import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.dto.MessagePublishFailedEvent
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.outbox.dto.NewMessagePublishedEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.EntityManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val logger = KotlinLogging.logger {}

@Service
class SlackMessageRelayServiceImpl(
    private val outboxRepository: MessageOutboxRepository,
    private val eventPublisher: ApplicationEventPublisher,
): MessageRelayService {

    @Async
    fun dispatchPendingMessagesBatch(pendingMessages: List<OutboxMessage>) {
        //TODO Dispatch pending messages.
//        for (message in pendingMessages){
//            try{
//
//            }
//        }
    }

    @Transactional
    override fun dispatchPendingMessages() {
        //TODO retry count check
        val pageSize = 100
        var offset = 0
        while (true) {
            val pendingMessages = this.outboxRepository.findPendingMessages(
                limit = pageSize,
                offset = offset
            )
            if (pendingMessages.isEmpty()) break

            this.dispatchPendingMessagesBatch(pendingMessages)
            offset += pendingMessages.size
        }
    }

    @Transactional
    @Retryable(
        maxAttempts = 3,
        backoff = Backoff(delay = 1000),
        retryFor = [ObjectOptimisticLockingFailureException::class]
    )
    @EventListener
    fun saveOutboxMessages(event: NewMessagePublishedEvent){
        logger.debug { "Save Outbox Message from ${event.reason}" }
        this.outboxRepository.save(event.outboxMessage)
    }

    /**
     * FIXME outbox repository doesn't update any changes.
     * update query not executed.
     */
//    @Retryable(
//        maxAttempts = 3,
//        backoff = Backoff(delay = 1000),
//        retryFor = [ObjectOptimisticLockingFailureException::class]
//    )
    @Transactional
    @EventListener
    fun updateSuccessfulMessage(event: MessagePublishSuccessEvent){
        logger.info { "Published Successfully. Update Outbox Message ${event.idempotencyKey}" }
        this.updateMessageWithTransaction(status = MessageStatus.SUCCESS, idempotencyKey = event.idempotencyKey)
    }

//    @Retryable(
//        maxAttempts = 3,
//        backoff = Backoff(delay = 1000),
//        retryFor = [ObjectOptimisticLockingFailureException::class]
//    )
    @Transactional
    @EventListener
    fun updateFailedMessage(event: MessagePublishFailedEvent){
        logger.info { "Published Failed. Update Outbox Message ${event.idempotencyKey}" }
        this.updateMessageWithTransaction(status = MessageStatus.FAILURE, idempotencyKey = event.idempotencyKey)
    }

    @Transactional
    fun updateMessageWithTransaction(status: MessageStatus, idempotencyKey: String): OutboxMessage {
        val message = this.outboxRepository.findById(idempotencyKey)
            .orElseThrow { throw RuntimeException("Message Not Found.") }
        message.updateMessageStatus(status = status)
        return this.outboxRepository.save(message)
    }
}