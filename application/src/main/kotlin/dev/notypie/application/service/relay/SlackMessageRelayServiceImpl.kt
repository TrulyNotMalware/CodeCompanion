@file:JvmName("MessageRelayServiceKt")

package dev.notypie.application.service.relay

import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.dto.NewMessagePublishedEvent
import dev.notypie.repository.outbox.dto.OutboxUpdateEvent
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
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class SlackMessageRelayServiceImpl(
    private val outboxRepository: MessageOutboxRepository,
    private val messageDispatcher: MessageDispatcher,
    private val retryService: RetryService
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

    @Retryable(
        maxAttempts = 5,
        backoff = Backoff(delay = 1000),
        retryFor = [ObjectOptimisticLockingFailureException::class]
    )
    @Transactional
    @EventListener
    fun saveOutboxMessages(event: NewMessagePublishedEvent){
        logger.debug { "Save Outbox Message from ${event.reason}" }
        this.outboxRepository.save(event.outboxMessage)
    }

//    @Retryable(
//        maxAttempts = 5,
//        backoff = Backoff(delay = 1000),
//        retryFor = [ObjectOptimisticLockingFailureException::class]
//    )
    @Transactional
    @EventListener
    fun updateOutboxMessageStatus(event: OutboxUpdateEvent) =
        this.retryService.execute(
            action = { this.updateMessage(status = event.status, idempotencyKey = event.idempotencyKey) },
            maxAttempts = 5
        )

    fun updateMessage(status: MessageStatus, idempotencyKey: UUID): OutboxMessage {
        val message = this.outboxRepository.findById(idempotencyKey.toString())
            .orElseThrow { throw RuntimeException("Message Not Found.") }
        message.updateMessageStatus(status = status)
        return this.outboxRepository.save(message)
    }
}