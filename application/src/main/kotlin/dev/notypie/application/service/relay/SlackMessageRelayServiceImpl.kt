@file:JvmName("MessageRelayServiceKt")

package dev.notypie.application.service.relay

import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.dto.MessagePublishFailedEvent
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.outbox.dto.NewMessagePublishedEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val messageDispatcher: MessageDispatcher
): MessageRelayService {

    override fun batchPendingMessages(pendingMessages: List<OutboxMessage>) {
        pendingMessages.forEach { message ->
            batchPendingMessagesAsync(message)
        }
    }

    @Async
    protected fun batchPendingMessagesAsync(pendingMessage: OutboxMessage){
        val result = messageDispatcher.dispatch(event = pendingMessage.toSlackEvent())
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