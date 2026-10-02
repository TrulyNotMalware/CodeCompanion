package dev.notypie.application.service.relay

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.repository.outbox.schema.MessageStatus
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.support.serializer.SerializationUtils
import java.util.UUID

fun createOutboxAfterImage(
    eventId: String = UUID.randomUUID().toString(),
    idempotencyKey: String = UUID.randomUUID().toString(),
    status: MessageStatus = MessageStatus.PENDING,
    publisherId: String = TEST_USER_ID,
    payload: String = "{}",
    attemptCount: Int = 0,
): Map<String, Any> =
    mapOf(
        "event_id" to eventId,
        "idempotency_key" to idempotencyKey,
        "publisher_id" to publisherId,
        "transport" to "SLACK",
        "payload" to payload,
        "status" to status.name,
        "version" to 0L,
        "schema_version" to 2,
        "created_at" to 1_777_000_000_000_000L,
        "attempt_count" to attemptCount,
    )

fun createCdcEnvelope(after: Map<String, Any>? = createOutboxAfterImage(), op: String = "c"): Envelope =
    Envelope(payload = Payload(op = op, after = after))

fun createCdcConsumerRecord(
    value: Any? = createCdcEnvelope(),
    undeserializableValue: ByteArray? = null,
    topic: String = "cdc.code_companion.outbox_message",
    partition: Int = 3,
    offset: Long = 42L,
): ConsumerRecord<String, Any?> =
    ConsumerRecord<String, Any?>(topic, partition, offset, UUID.randomUUID().toString(), value).also { record ->
        undeserializableValue?.let {
            SerializationUtils.deserializationException(
                record.headers(),
                it,
                IllegalStateException("Cannot deserialize CDC value"),
                false,
            )
        }
    }
