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
