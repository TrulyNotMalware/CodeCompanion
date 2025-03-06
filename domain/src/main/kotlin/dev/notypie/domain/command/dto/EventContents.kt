package dev.notypie.domain.command.dto

import dev.notypie.domain.command.entity.CommandDetailType
import java.time.temporal.ChronoUnit

interface SlackEvent

data class PostEventContents(
    val messageType: MessageType,
    val apiAppId: String,
    val commandDetailType: CommandDetailType,
    val idempotencyKey: String,
    val publisherId: String,

    val replaceOriginal: Boolean,
    val channel: String,
    val body: Map<String, Any>,
): SlackEvent

data class ActionEventContents(
    val apiAppId: String,
    val commandDetailType: CommandDetailType,
    val idempotencyKey: String,
    val publisherId: String,

    val responseUrl: String,
    val channel: String,
    val body: String,
): SlackEvent

data class DelayHandleEventContents(
    val apiAppId: String,
    val delayTime: Long = 5L,
    val timeUnit: ChronoUnit = ChronoUnit.MINUTES,
    val commandDetailType: CommandDetailType,
    val idempotencyKey: String,
    val channel: String,
    val publisherId: String,
): SlackEvent

enum class MessageType{
    CHANNEL_ALERT,
    EPHEMERAL_MESSAGE,
    DIRECT_MESSAGE,
    ACTION_RESPONSE
}