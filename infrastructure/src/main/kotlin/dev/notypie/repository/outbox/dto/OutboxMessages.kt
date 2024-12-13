package dev.notypie.repository.outbox.dto

data class MessagePublishFailedEvent(
    val idempotencyKey: String,
    val reason: String
)