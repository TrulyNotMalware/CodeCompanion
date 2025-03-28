package dev.notypie.domain.command.dto

import dev.notypie.domain.command.entity.CommandDetailType
import java.time.temporal.ChronoUnit

sealed class SlackEvent(
    open val apiAppId: String,
    open val commandDetailType: CommandDetailType,
    open val idempotencyKey: String,
    open val publisherId: String,
    open val channel: String
)

data class PostEventContents(
    override val apiAppId: String,
    val messageType: MessageType,
    override val commandDetailType: CommandDetailType,
    override val idempotencyKey: String,
    override val publisherId: String,
    override val channel: String,

    val replaceOriginal: Boolean,
    val body: Map<String, Any>,
): SlackEvent(
    apiAppId=apiAppId, commandDetailType=commandDetailType,
    idempotencyKey=idempotencyKey, publisherId=publisherId, channel=channel
)

data class ActionEventContents(
    override val apiAppId: String,
    override val commandDetailType: CommandDetailType,
    override val idempotencyKey: String,
    override val publisherId: String,
    override val channel: String,

    val responseUrl: String,
    val body: String,
): SlackEvent(
    apiAppId=apiAppId, commandDetailType=commandDetailType,
    idempotencyKey=idempotencyKey, publisherId=publisherId, channel=channel
)


data class DelayHandleEventContents(
    override val apiAppId: String,
    val delayTime: Long = 5L,
    val timeUnit: ChronoUnit = ChronoUnit.MINUTES,
    override val commandDetailType: CommandDetailType,
    override val idempotencyKey: String,
    override val channel: String,
    override val publisherId: String,
): SlackEvent(
    apiAppId=apiAppId, commandDetailType=commandDetailType,
    idempotencyKey=idempotencyKey, publisherId=publisherId, channel=channel
)


enum class MessageType{
    CHANNEL_ALERT,
    EPHEMERAL_MESSAGE,
    DIRECT_MESSAGE,
    ACTION_RESPONSE
}