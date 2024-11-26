package dev.notypie.domain.command.dto

import dev.notypie.domain.command.entity.CommandDetailType
import java.util.concurrent.TimeUnit

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

data class DelayHandleEventContents(
    val apiAppId: String,
    val delayTime: Long = 5L,
    val timeUnit: TimeUnit = TimeUnit.MINUTES,
    val idempotencyKey: String,
    val channel: String,
    val publisherId: String,
)

enum class MessageType{
    TO_ALL,
    EPHEMERAL
}