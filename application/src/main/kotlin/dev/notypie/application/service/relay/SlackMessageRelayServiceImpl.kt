package dev.notypie.application.service.relay

import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.domain.command.entity.event.SendSlackMessageEvent
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.dto.MessagePublishFailedEvent
import dev.notypie.repository.outbox.dto.NewMessagePublishedEvent
import dev.notypie.repository.outbox.dto.OutboxUpdateEvent
import dev.notypie.repository.outbox.dto.toOutboxUpdateEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.outbox.schema.toOutboxMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID
import java.util.concurrent.Executor

private val logger = KotlinLogging.logger {}

@Service
class SlackMessageRelayServiceImpl(
    private val outboxRepository: MessageOutboxRepository,
    private val messageDispatcher: MessageDispatcher,
    private val retryService: RetryService,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val relayTaskExecutor: Executor,
) : MessageRelayService {
    /**
     * Submits each PENDING message to [relayTaskExecutor] directly. We cannot use
     * Spring's [@Async] here because [batchPendingMessagesAsync] would be invoked
     * from within the same bean; self-invocation bypasses the AOP proxy and the
     * dispatch would run synchronously on the polling scheduler thread.
     */
    override fun batchPendingMessages(pendingMessages: List<OutboxMessage>) {
        pendingMessages.forEach { message ->
            relayTaskExecutor.execute { batchPendingMessagesAsync(pendingMessage = message) }
        }
    }

    /**
     * Dispatches a PENDING outbox message and publishes an [OutboxUpdateEvent] with the
     * dispatch result so that [updateOutboxMessageStatus] transitions the row out of
     * PENDING. Without this publish step, the polling loop would re-read the same rows
     * forever.
     *
     * Note: we catch [Exception] (not [Throwable]) intentionally. [Error] subclasses
     * (OutOfMemoryError, StackOverflowError, etc.) indicate fatal JVM conditions and
     * should propagate to the executor's uncaught handler.
     */
    internal fun batchPendingMessagesAsync(pendingMessage: OutboxMessage) {
        // Parse eventId up front. If this fails the row cannot be identified by UUID
        // so we log and skip rather than publishing a status event under a bogus key.
        val eventId =
            runCatching { UUID.fromString(pendingMessage.eventId) }
                .getOrElse { parseFailure ->
                    logger.error(parseFailure) {
                        "Skipping outbox row with malformed eventId='${pendingMessage.eventId}' " +
                            "idempotencyKey=${pendingMessage.idempotencyKey}"
                    }
                    return
                }

        val updateEvent: OutboxUpdateEvent =
            try {
                val result = messageDispatcher.dispatch(event = pendingMessage.toSlackEvent())
                result.toOutboxUpdateEvent(eventId = eventId)
            } catch (exception: Exception) {
                logger.error(exception) {
                    "Dispatch failed for eventId=$eventId idempotencyKey=${pendingMessage.idempotencyKey}"
                }
                MessagePublishFailedEvent(
                    eventId = eventId,
                    reason = exception.toString(),
                )
            }
        applicationEventPublisher.publishEvent(updateEvent)
    }

    @Transactional
    @EventListener
    fun saveOutboxMessages(event: NewMessagePublishedEvent) {
        logger.debug { "Save Outbox Message from ${event.reason}" }
        outboxRepository.save(event.outboxMessage)
    }

    @Transactional
    @EventListener
    fun updateOutboxMessageStatus(event: OutboxUpdateEvent) =
        retryService.execute(
            action = { updateMessage(status = event.status, eventId = event.eventId) },
            maxAttempts = 5,
        )

    fun updateMessage(status: MessageStatus, eventId: UUID): OutboxMessage {
        val message =
            outboxRepository
                .findById(eventId.toString())
                .orElseThrow { throw RuntimeException("Message Not Found.") }
        message.updateMessageStatus(status = status)
        return outboxRepository.save(message)
    }

    // Domain Event Listener
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun saveOutboxMessage(event: SendSlackMessageEvent) {
        retryService.execute(
            action = { outboxRepository.save(event.toOutboxMessage()) },
            maxAttempts = 3,
        )
    }
}
