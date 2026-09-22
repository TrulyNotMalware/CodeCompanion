package dev.notypie.application.service.relay

import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.command.isRateLimited
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.dto.MessagePublishFailedEvent
import dev.notypie.repository.outbox.dto.OutboxUpdateEvent
import dev.notypie.repository.outbox.dto.toOutboxUpdateEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.outbox.schema.toOutboxMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Payload
import java.util.UUID

private val logger = KotlinLogging.logger {}

// Deterministic: the record can never be processed, so the error handler must not retry it before the DLT.
class CdcRecordParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class DebeziumLogTailingProcessor(
    private val messageDispatcher: MessageDispatcher,
    private val payloadRenderer: OutboxPayloadRenderer,
    private val eventPublisher: ApplicationEventPublisher,
    private val outboxRepository: MessageOutboxRepository,
) : MessageProcessor {
    @KafkaListener(
        topics = ["\${slack.app.mode.cdc.topic}"],
        containerFactory = "concurrentKafkaListenerContainerFactory",
        properties = [
            "spring.json.use.type.headers:false",
            "spring.json.value.default.type=dev.notypie.application.service.relay.Envelope",
        ],
    )
    fun consume(
        @Payload(required = false) envelope: Envelope?,
    ) {
        if (envelope == null) {
            logger.warn { "Skipping CDC tombstone record." }
            return
        }
        // No `after` = delete event; non-PENDING = the UPDATE events this processor itself causes.
        val afterImage = envelope.payload.after ?: return
        val snapshot: OutboxMessage =
            try {
                afterImage.toMutableMap().toOutboxMessage()
            } catch (exception: Exception) {
                throw CdcRecordParseException(message = "Failed to parse CDC after-image", cause = exception)
            }
        if (snapshot.status != MessageStatus.PENDING.name) return

        val eventId =
            runCatching { UUID.fromString(snapshot.eventId) }
                .getOrElse { parseFailure ->
                    throw CdcRecordParseException(
                        message = "Malformed eventId='${snapshot.eventId}' idempotencyKey=${snapshot.idempotencyKey}",
                        cause = parseFailure,
                    )
                }

        if (!claimForDispatch(eventId = eventId, idempotencyKey = snapshot.idempotencyKey)) return

        val updateEvent: OutboxUpdateEvent =
            try {
                val rendered = payloadRenderer.render(row = snapshot)
                val dispatchResult = messageDispatcher.dispatch(event = rendered)
                if (dispatchResult.isRateLimited()) {
                    logger.warn { "Slack rate limit; leaving eventId=$eventId IN_PROGRESS for the recovery sweep" }
                    return
                }
                dispatchResult.toOutboxUpdateEvent(eventId = eventId)
            } catch (exception: Exception) {
                logger.error(exception) {
                    "CDC dispatch failed for eventId=$eventId idempotencyKey=${snapshot.idempotencyKey}"
                }
                MessagePublishFailedEvent(eventId = eventId, reason = exception.toString())
            }
        eventPublisher.publishEvent(updateEvent)
    }

    // The after-image is a log snapshot; a redelivery may arrive after the row already moved on.
    private fun claimForDispatch(eventId: UUID, idempotencyKey: String): Boolean {
        val current = outboxRepository.findById(eventId.toString()).orElse(null)
        if (current == null) {
            logger.warn { "Outbox row missing for eventId=$eventId idempotencyKey=$idempotencyKey; skipping." }
            return false
        }
        return when (current.status) {
            MessageStatus.PENDING.name -> outboxRepository.claimPending(eventIds = listOf(eventId.toString())) == 1
            MessageStatus.IN_PROGRESS.name -> {
                logger.warn { "Re-dispatching eventId=$eventId left IN_PROGRESS by an interrupted consumer." }
                true
            }
            else -> {
                logger.info { "Skipping redelivered eventId=$eventId already ${current.status}." }
                false
            }
        }
    }
}
