package dev.notypie.application.service.relay

import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.outbox.schema.toOutboxMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Payload
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

private val logger = KotlinLogging.logger {}

// Deterministic: the record can never be processed, so the error handler must not retry it before the DLT.
class CdcRecordParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

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
