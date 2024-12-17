package dev.notypie.domain.command.dto

// FIXME IdempotencyKey must be difference in context.
data class CommandBasicInfo(
    val appId: String,
    val appToken: String,
    val publisherId: String,
    val channel: String,
    val idempotencyKey: String,
)