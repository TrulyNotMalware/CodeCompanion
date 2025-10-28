package dev.notypie.domain.command

import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_TOKEN
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.common.event.ActionEventPayloadContents
import dev.notypie.domain.common.event.PostEventPayloadContents
import dev.notypie.domain.common.event.SendSlackMessageEvent
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
) = PostEventPayloadContents(
    apiAppId = appId,
    commandDetailType = commandDetailType,
    idempotencyKey = idempotencyKey,
    publisherId = publisherId,
    channel = channel,
    eventId = UUID.randomUUID(),
    messageType = toMessageTypeByTargetUser(targetUserId = targetUserId),
    replaceOriginal = false,
    body = mapOf(),
)

fun createActionEventPayloadContents(
    commandDetailType: CommandDetailType,
    body: String = "",
    appId: String = TEST_APP_ID,
    publisherId: String = TEST_USER_ID,
    channel: String = TEST_CHANNEL_ID,
    idempotencyKey: UUID = UUID.randomUUID(),
) = ActionEventPayloadContents(
    eventId = UUID.randomUUID(),
    apiAppId = appId,
    publisherId = publisherId,
    channel = channel,
    idempotencyKey = idempotencyKey,
    commandDetailType = commandDetailType,
    body = body,
    responseUrl = "",
)

fun createSendSlackMessageEvent(
    commandDetailType: CommandDetailType,
    idempotencyKey: UUID,
    isPostEventPayload: Boolean = true,
    targetUserId: String? = null,
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
            )
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
