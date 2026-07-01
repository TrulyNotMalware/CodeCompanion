package dev.notypie.domain.command.entity.event

import dev.notypie.domain.command.entity.CommandDetailType
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
    )

/**
 * Payload for a synchronous `views.open` call. Unlike [PostEventPayloadContents] (outbox-relayed),
 * this must be dispatched inline on the request thread because `trigger_id` expires 3s after issuance.
 */
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
    )

enum class MessageType {
    CHANNEL_ALERT,
    EPHEMERAL_MESSAGE,
    DIRECT_MESSAGE,
    ACTION_RESPONSE,

    /** `chat.update` — rewrites an existing message in place using `channel` + `ts`. */
    UPDATE_MESSAGE,
}

fun toMessageTypeByTargetUser(targetUserId: String?): MessageType =
    if (targetUserId.isNullOrBlank()) MessageType.CHANNEL_ALERT else MessageType.DIRECT_MESSAGE
