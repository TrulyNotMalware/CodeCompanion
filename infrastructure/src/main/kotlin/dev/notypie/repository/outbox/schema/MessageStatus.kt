package dev.notypie.repository.outbox.schema

enum class MessageStatus {
    INIT,
    FAILURE,
    SUCCESS,
    PENDING,

    IN_PROGRESS,
}
