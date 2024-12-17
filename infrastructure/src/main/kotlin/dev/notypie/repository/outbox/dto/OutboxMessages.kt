package dev.notypie.repository.outbox.dto

import dev.notypie.repository.outbox.schema.OutboxMessage

data class MessagePublishFailedEvent(
    val idempotencyKey: String,
    val reason: String
)

data class MessagePublishSuccessEvent(
    val idempotencyKey: String
)

data class NewMessagePublishedEvent(
    val reason: String,
    val outboxMessage: OutboxMessage
)