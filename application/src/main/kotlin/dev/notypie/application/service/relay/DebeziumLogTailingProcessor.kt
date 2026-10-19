package dev.notypie.application.service.relay

import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.outbox.schema.toOutboxMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.messaging.handler.annotation.Payload
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

private val logger = KotlinLogging.logger {}

class CdcRecordParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ListenerErrorException(
    cause: Error,
) : RuntimeException("CDC listener hit ${cause::class.java.name}", cause)

class DebeziumLogTailingProcessor(
    private val outboxRepository: MessageOutboxRepository,
    private val relayService: MessageRelayService,
    private val clock: Clock,
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
        @Header(name = KafkaHeaders.RECEIVED_PARTITION, required = false) partition: Int? = null,
        @Header(name = KafkaHeaders.OFFSET, required = false) offset: Long? = null,
    ) {
        try {
            process(envelope = envelope)
        } catch (error: Error) {
            logger.error(error) {
                "CDC record partition=${partition ?: "unknown"} offset=${offset ?: "unknown"} " +
                    "eventId=${envelope?.payload?.after?.get("event_id") ?: "unknown"} hit " +
                    "${error::class.java.name}; handing it to the container's error handler"
            }
            throw ListenerErrorException(cause = error)
        }
    }

    private fun process(envelope: Envelope?) {
        if (envelope == null) {
            logger.debug { "Skipping CDC tombstone record." }
            return
        }
        val afterImage = envelope.payload.after ?: return
        val snapshot: OutboxMessage =
            try {
                afterImage.toMutableMap().toOutboxMessage()
            } catch (exception: Exception) {
                throw CdcRecordParseException(message = "Failed to parse CDC after-image", cause = exception)
            }
        if (snapshot.status != MessageStatus.PENDING.name) return

        val eventId =
            try {
                UUID.fromString(snapshot.eventId)
            } catch (malformed: IllegalArgumentException) {
                throw CdcRecordParseException(
                    message = "Malformed eventId='${snapshot.eventId}' idempotencyKey=${snapshot.idempotencyKey}",
                    cause = malformed,
                )
            }

        val current = outboxRepository.findByIdOrNull(eventId.toString())
        if (current == null) {
            logger.warn { "Outbox row missing for eventId=$eventId idempotencyKey=${snapshot.idempotencyKey}" }
            return
        }
        if (current.status != MessageStatus.PENDING.name) {
            logger.info { "Skipping eventId=$eventId already ${current.status}; only a PENDING row is claimed here" }
            return
        }
        val claim = outboxRepository.claim(row = current, now = LocalDateTime.now(clock)) ?: return
        relayService.dispatchClaimed(claim = claim)
    }
}
