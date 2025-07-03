package dev.notypie.domain.common.event

import dev.notypie.domain.command.entity.CommandDetailType
import java.time.temporal.ChronoUnit
import java.util.UUID

sealed class SlackEventPayload(
    open val apiAppId: String,
    open val commandDetailType: CommandDetailType,
    open val idempotencyKey: UUID,
    open val publisherId: String,
    open val channel: String
): EventPayload

data class PostEventPayloadContents(
    override val eventId: UUID,
    override val apiAppId: String,
    val messageType: MessageType,
    override val commandDetailType: CommandDetailType,
    override val idempotencyKey: UUID,
    override val publisherId: String,
    override val channel: String,

    val replaceOriginal: Boolean,
    val body: Map<String, Any>,
): SlackEventPayload(
    apiAppId=apiAppId, commandDetailType=commandDetailType,
    idempotencyKey=idempotencyKey, publisherId=publisherId, channel=channel
)

data class ActionEventPayloadContents(
    override val eventId: UUID,
    override val apiAppId: String,
    override val commandDetailType: CommandDetailType,
    override val idempotencyKey: UUID,
    override val publisherId: String,
    override val channel: String,

    val responseUrl: String,
    val body: String,
): SlackEventPayload(
    apiAppId=apiAppId, commandDetailType=commandDetailType,
    idempotencyKey=idempotencyKey, publisherId=publisherId, channel=channel
)


data class DelayHandleEventPayloadContents(
    override val eventId: UUID,
    override val apiAppId: String,
    val delayTime: Long = 5L,
    val timeUnit: ChronoUnit = ChronoUnit.MINUTES,
    override val commandDetailType: CommandDetailType,
    override val idempotencyKey: UUID,
    override val channel: String,
    override val publisherId: String,
): SlackEventPayload(
    apiAppId=apiAppId, commandDetailType=commandDetailType,
    idempotencyKey=idempotencyKey, publisherId=publisherId, channel=channel
)


enum class MessageType{
    CHANNEL_ALERT,
    EPHEMERAL_MESSAGE,
    DIRECT_MESSAGE,
    ACTION_RESPONSE
}