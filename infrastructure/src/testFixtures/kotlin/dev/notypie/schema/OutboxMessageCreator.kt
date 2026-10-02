package dev.notypie.schema

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.repository.outbox.Transport
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import java.time.LocalDateTime
import java.util.UUID

fun createOutboxMessage(
    eventId: String = UUID.randomUUID().toString(),
    idempotencyKey: String = UUID.randomUUID().toString(),
    publisherId: String = TEST_USER_ID,
    payload: String = "{}",
    createdAt: LocalDateTime = LocalDateTime.now(),
    status: MessageStatus = MessageStatus.PENDING,
): OutboxMessage =
    OutboxMessage(
        eventId = eventId,
        idempotencyKey = idempotencyKey,
        publisherId = publisherId,
        transport = Transport.SLACK.name,
        payload = payload,
        createdAt = createdAt,
    ).apply { updateMessageStatus(status = status) }

fun createOutboxColumnMap(
    eventId: String = UUID.randomUUID().toString(),
    createdAt: Long,
    updatedAt: Long? = null,
): MutableMap<String, Any> =
    mutableMapOf<String, Any>(
        "event_id" to eventId,
        "idempotency_key" to UUID.randomUUID().toString(),
        "publisher_id" to TEST_USER_ID,
        "transport" to Transport.SLACK.name,
        "payload" to "{}",
        "created_at" to createdAt,
        "schema_version" to 2,
        "attempt_count" to 0,
        "send_count" to 0,
        "status" to MessageStatus.PENDING.name,
    ).apply { if (updatedAt != null) put("updated_at", updatedAt) }
