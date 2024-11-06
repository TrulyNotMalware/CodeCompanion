package dev.notypie.domain.command.dto

import dev.notypie.domain.command.entity.CommandDetailType

data class PostEventContents(
    val messageType: MessageType,
    val apiAppId: String,
    val commandDetailType: CommandDetailType,
    val idempotencyKey: String,
    val publisherId: String,

    val replaceOriginal: Boolean,
    val channel: String,
    val body: Map<String, String>,
)

data class ActionEventContents(
    val apiAppId: String,
    val commandDetailType: CommandDetailType,
    val idempotencyKey: String,
    val publisherId: String,

    val responseUrl: String,
    val channel: String,
    val body: String,
)

enum class MessageType{
    TO_ALL,
    EPHEMERAL
}