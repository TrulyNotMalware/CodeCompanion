package dev.notypie.domain.command

import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.TEST_BASE_URL
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_TOKEN
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.ActionEventPayloadContents
import dev.notypie.domain.command.entity.event.MessageType
import dev.notypie.domain.command.entity.event.OpenViewEvent
import dev.notypie.domain.command.entity.event.OpenViewPayloadContents
import dev.notypie.domain.command.entity.event.PostEventPayloadContents
import dev.notypie.domain.command.entity.event.SendSlackMessageEvent
import dev.notypie.domain.command.entity.event.toMessageTypeByTargetUser
import java.util.UUID

fun createCommandBasicInfo(
    appId: String = TEST_APP_ID,
    appToken: String = TEST_TOKEN,
    publisherId: String = TEST_USER_ID,
    channel: String = TEST_CHANNEL_ID,
    idempotencyKey: UUID = UUID.randomUUID(),
) = CommandBasicInfo(
    appId = appId,
    appToken = appToken,
    publisherId = publisherId,
    channel = channel,
    idempotencyKey = idempotencyKey,
)

fun createPostEventPayloadContents(
    commandDetailType: CommandDetailType,
    targetUserId: String? = null,
    appId: String = TEST_APP_ID,
    publisherId: String = TEST_USER_ID,
    channel: String = TEST_CHANNEL_ID,
    idempotencyKey: UUID = UUID.randomUUID(),
    body: Map<String, Any> = mapOf(),
) = PostEventPayloadContents(
    apiAppId = appId,
    commandDetailType = commandDetailType,
    idempotencyKey = idempotencyKey,
    publisherId = publisherId,
    channel = channel,
    eventId = UUID.randomUUID(),
    messageType = toMessageTypeByTargetUser(targetUserId = targetUserId),
    replaceOriginal = false,
    body = body,
)

fun createActionEventPayloadContents(
    commandDetailType: CommandDetailType,
    body: String = "",
    appId: String = TEST_APP_ID,
    publisherId: String = TEST_USER_ID,
    channel: String = TEST_CHANNEL_ID,
    idempotencyKey: UUID = UUID.randomUUID(),
    responseUrl: String = TEST_BASE_URL,
) = ActionEventPayloadContents(
    eventId = UUID.randomUUID(),
    apiAppId = appId,
    publisherId = publisherId,
    channel = channel,
    idempotencyKey = idempotencyKey,
    commandDetailType = commandDetailType,
    body = body,
    responseUrl = responseUrl,
)

fun createSendSlackMessageEvent(
    commandDetailType: CommandDetailType,
    idempotencyKey: UUID,
    isPostEventPayload: Boolean = true,
    targetUserId: String? = null,
    messageType: MessageType? = null,
    appId: String = TEST_APP_ID,
    publisherId: String = TEST_USER_ID,
    channel: String = TEST_CHANNEL_ID,
) = SendSlackMessageEvent(
    idempotencyKey = idempotencyKey,
    payload =
        if (isPostEventPayload) {
            createPostEventPayloadContents(
                idempotencyKey = idempotencyKey,
                appId = appId,
                publisherId = publisherId,
                channel = channel,
                targetUserId = targetUserId,
                commandDetailType = commandDetailType,
            ).let { if (messageType != null) it.copy(messageType = messageType) else it }
        } else {
            createActionEventPayloadContents(
                idempotencyKey = idempotencyKey,
                appId = appId,
                publisherId = publisherId,
                channel = channel,
                commandDetailType = commandDetailType,
            )
        },
    destination = "",
    timestamp = System.currentTimeMillis(),
    type = commandDetailType,
)

fun createOpenViewEvent(
    idempotencyKey: UUID = UUID.randomUUID(),
    appId: String = TEST_APP_ID,
    publisherId: String = TEST_USER_ID,
    channel: String = TEST_CHANNEL_ID,
    commandDetailType: CommandDetailType = CommandDetailType.DECLINE_REASON_MODAL,
    triggerId: String = "",
    meetingIdempotencyKey: UUID = UUID.randomUUID(),
    participantUserId: String = TEST_USER_ID,
    viewJson: String = "{}",
) = OpenViewEvent(
    idempotencyKey = idempotencyKey,
    payload =
        OpenViewPayloadContents(
            eventId = UUID.randomUUID(),
            apiAppId = appId,
            commandDetailType = commandDetailType,
            idempotencyKey = idempotencyKey,
            publisherId = publisherId,
            channel = channel,
            triggerId = triggerId,
            viewJson = viewJson,
            meetingIdempotencyKey = meetingIdempotencyKey,
            participantUserId = participantUserId,
        ),
    type = commandDetailType,
)

fun createApprovalContents(
    idempotencyKey: UUID = UUID.randomUUID(),
    commandDetailType: CommandDetailType = CommandDetailType.SIMPLE_TEXT,
    reason: String = "Test reason",
    publisherId: String = TEST_USER_ID,
    headLineText: String? = null,
) = ApprovalContents(
    idempotencyKey = idempotencyKey,
    commandDetailType = commandDetailType,
    reason = reason,
    publisherId = publisherId,
    headLineText = headLineText ?: "Approval Requests",
)
