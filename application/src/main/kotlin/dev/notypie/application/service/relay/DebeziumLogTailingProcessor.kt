package dev.notypie.application.service.relay

import dev.notypie.application.service.relay.dto.Envelope
import dev.notypie.application.service.relay.dto.MessageProcessorParameter
import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.repository.outbox.dto.MessagePublishFailedEvent
import dev.notypie.repository.outbox.dto.OutboxUpdateEvent
import dev.notypie.repository.outbox.dto.toOutboxUpdateEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.outbox.schema.toOutboxMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.kafka.annotation.KafkaListener
import java.util.UUID

private val logger = KotlinLogging.logger {}

class DebeziumLogTailingProcessor(
    private val messageDispatcher: MessageDispatcher,
    private val eventPublisher: ApplicationEventPublisher,
) : MessageProcessor {
    @KafkaListener(
        topics = ["\${slack.app.mode.cdc.topic}"],
        containerFactory = "concurrentKafkaListenerContainerFactory",
        properties = [
            "spring.json.use.type.headers:false",
            "spring.json.value.default.type=dev.notypie.application.service.relay.dto.Envelope",
        ],
    )
    override fun getPendingMessages(messageParameter: MessageProcessorParameter) {
        val consumeRecord = messageParameter as Envelope
        val outboxMessage: OutboxMessage =
            try {
                consumeRecord.payload.after
                    ?.toMutableMap()
                    ?.toOutboxMessage()
                    ?: return
            } catch (exception: Exception) {
                logger.error(exception) { "Failed to parse CDC payload; skipping record." }
                return
            }
        if (outboxMessage.status != MessageStatus.PENDING.name) return

        val eventId =
            runCatching { UUID.fromString(outboxMessage.eventId) }
                .getOrElse { parseFailure ->
                    logger.error(parseFailure) {
                        "Skipping CDC record with malformed eventId='${outboxMessage.eventId}' " +
                            "idempotencyKey=${outboxMessage.idempotencyKey}"
                    }
                    return
                }

        val updateEvent: OutboxUpdateEvent =
            try {
                val dispatchResult = messageDispatcher.dispatch(event = outboxMessage.toSlackEvent())
                dispatchResult.toOutboxUpdateEvent(eventId = eventId)
            } catch (exception: Exception) {
                logger.error(exception) {
                    "CDC dispatch failed for eventId=$eventId idempotencyKey=${outboxMessage.idempotencyKey}"
                }
                MessagePublishFailedEvent(eventId = eventId, reason = exception.toString())
            }
        eventPublisher.publishEvent(updateEvent)
    }
}
