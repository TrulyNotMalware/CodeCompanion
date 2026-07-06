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
     * Renders a PENDING outbox row to a transport payload, dispatches it, and publishes an
     * [OutboxUpdateEvent] carrying the dispatch result so that [updateOutboxMessageStatus]
     * transitions the row out of PENDING. Without this publish step, the polling loop would
     * re-read the same rows forever.
     *
     * The status event always keys on the ROW eventId (parsed up front), never on the payload
     * eventId the renderer mints — that one is throwaway; the row PK is the identity.
     *
     * Render and dispatch both sit inside the try: a malformed/unsupported payload surfaces as a
     * failure event, exactly as the old row-side decode did. Render is deliberately NOT retried
     * (retrying a codec/schema failure cannot help); only dispatch is retried, inside the
     * dispatcher.
     *
     * Note: we catch [Exception] (not [Throwable]) intentionally. [Error] subclasses
     * (OutOfMemoryError, StackOverflowError, etc.) indicate fatal JVM conditions and
     * should propagate to the executor's uncaught handler.
     */
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

    // The read-modify-write below relies on OutboxMessage's @Version optimistic lock, which is
    // enforced when save() (merge) flushes — the detached entity carries the version it was read
    // with, so a racing claim is still detected and retried. A service-level transaction would add
    // nothing here, so the persistence boundary stays in the repository's save().
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

    // Interactive outbound path: builds the transport-neutral row from the enqueued message inside
    // the command's transaction so it commits atomically with the command's own writes. Spring
    // Data's save() runs in its own transaction; no service-level boundary needed.
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
