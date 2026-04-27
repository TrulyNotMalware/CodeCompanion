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
 * Payload for a synchronous `views.open` call. Unlike [PostEventPayloadContents] — which is
 * staged to the outbox and relayed asynchronously — this must be dispatched inline on the
 * request thread because Slack's `trigger_id` expires 3 seconds after issuance. The
 * dispatcher-level failure path publishes [DeclineModalOpenFailedEvent] so the application
 * layer can record the decline with [dev.notypie.domain.command.dto.interactions.RejectReason.OTHER]
 * and notify the user.
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
    val meetingIdempotencyKey: UUID,
    val participantUserId: String,
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

    /**
     * `chat.update` — rewrites an existing message in place using `channel` + `ts` keys in
     * the form body. Never synthesized from user DMs directly; emitted by flows like the
     * decline-reason submission that need to collapse a prior interactive notice.
     */
    UPDATE_MESSAGE,
}

fun toMessageTypeByTargetUser(targetUserId: String?): MessageType =
    if (targetUserId.isNullOrBlank()) MessageType.CHANNEL_ALERT else MessageType.DIRECT_MESSAGE
