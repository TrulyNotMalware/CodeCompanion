package dev.notypie.application.service.relay

import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.command.event.OutboundMessageEnqueued
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.repository.outbox.dto.MessagePublishFailedEvent
import dev.notypie.repository.outbox.dto.OutboxUpdateEvent
import dev.notypie.repository.outbox.dto.toOutboxUpdateEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID
import java.util.concurrent.Executor

private val logger = KotlinLogging.logger {}

@Service
class SlackMessageRelayServiceImpl(
    private val outboxRepository: MessageOutboxRepository,
    private val outboundMessagePort: OutboundMessagePort,
    private val payloadRenderer: OutboxPayloadRenderer,
    private val messageDispatcher: MessageDispatcher,
    private val retryService: RetryService,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val relayTaskExecutor: Executor,
) : MessageRelayService {
    // Can't use @Async here — self-invocation from this bean would bypass the AOP proxy.
    override fun batchPendingMessages(pendingMessages: List<OutboxMessage>) {
        pendingMessages.forEach { message ->
            relayTaskExecutor.execute { batchPendingMessagesAsync(pendingMessage = message) }
        }
    }

    // Keyed on the row's eventId, not the renderer's payload eventId, which is throwaway.
    internal fun batchPendingMessagesAsync(pendingMessage: OutboxMessage) {
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
                val rendered = payloadRenderer.render(row = pendingMessage)
                val result = messageDispatcher.dispatch(event = rendered)
                result.toOutboxUpdateEvent(eventId = eventId)
            } catch (exception: Exception) {
                // Catches Exception, not Throwable, so fatal Errors (OOM, StackOverflow) still propagate.
                logger.error(exception) {
                    "Dispatch failed for eventId=$eventId idempotencyKey=${pendingMessage.idempotencyKey}"
                }
                MessagePublishFailedEvent(
                    eventId = eventId,
                    reason = exception.toString(),
                )
            }
        // Publishing is required so the row leaves PENDING; skipping it makes polling re-read it forever.
        applicationEventPublisher.publishEvent(updateEvent)
    }

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

    // Runs inside the command's tx via BEFORE_COMMIT so the row commits atomically with it.
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun saveOutboxMessage(event: OutboundMessageEnqueued) {
        retryService.execute(
            action = {
                val row =
                    outboundMessagePort.toRow(
                        message = event.payload.message,
                        basicInfo = event.payload.basicInfo,
                    )
                outboxRepository.save(row)
            },
            maxAttempts = 3,
        )
    }
}
