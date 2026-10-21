package dev.notypie.impl.command.event

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.EventPayload
import java.util.UUID

sealed class SlackEventPayload(
    open val apiAppId: String,
    open val commandDetailType: CommandDetailType,
    open val idempotencyKey: UUID,
    open val publisherId: String,
    open val channel: String,
) : EventPayload

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
) : SlackEventPayload(
        apiAppId = apiAppId,
        commandDetailType = commandDetailType,
        idempotencyKey = idempotencyKey,
        publisherId = publisherId,
        channel = channel,
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
) : SlackEventPayload(
        apiAppId = apiAppId,
        commandDetailType = commandDetailType,
        idempotencyKey = idempotencyKey,
        publisherId = publisherId,
        channel = channel,
    ) {
    override fun toString(): String =
        "ActionEventPayloadContents(eventId=$eventId, apiAppId=$apiAppId, commandDetailType=$commandDetailType, " +
            "idempotencyKey=$idempotencyKey, publisherId=$publisherId, channel=$channel, responseUrl=redacted, " +
            "body=$body)"
}

data class OpenViewPayloadContents(
    override val eventId: UUID,
    override val apiAppId: String,
    override val commandDetailType: CommandDetailType,
    override val idempotencyKey: UUID,
    override val publisherId: String,
    override val channel: String,
    val triggerId: String,
    val viewJson: String,
    val meetingIdempotencyKey: UUID? = null,
    val participantUserId: String = "",
) : SlackEventPayload(
        apiAppId = apiAppId,
        commandDetailType = commandDetailType,
        idempotencyKey = idempotencyKey,
        publisherId = publisherId,
        channel = channel,
    ) {
    override fun toString(): String =
        "OpenViewPayloadContents(eventId=$eventId, apiAppId=$apiAppId, commandDetailType=$commandDetailType, " +
            "idempotencyKey=$idempotencyKey, publisherId=$publisherId, channel=$channel, triggerId=$triggerId, " +
            "viewJson=redacted, meetingIdempotencyKey=$meetingIdempotencyKey, participantUserId=$participantUserId)"
}

enum class MessageType {
    CHANNEL_ALERT,
    EPHEMERAL_MESSAGE,
    DIRECT_MESSAGE,
    UPDATE_MESSAGE,
}

fun toMessageTypeByTargetUser(targetUserId: String?): MessageType =
    if (targetUserId.isNullOrBlank()) MessageType.CHANNEL_ALERT else MessageType.DIRECT_MESSAGE
