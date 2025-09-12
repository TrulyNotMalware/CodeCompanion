package dev.notypie.domain.command

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.common.event.ActionEventPayloadContents
import dev.notypie.domain.common.event.PostEventPayloadContents
import dev.notypie.domain.common.event.SendSlackMessageEvent
import java.util.UUID

const val TEST_APP_ID = "A12ABCDEFG" // starts with A
const val TEST_USER_ID = "U012ABCDEFG" // starts with U
const val TEST_USER_NAME = "I_AM_TEST_USER"
const val TEST_CHANNEL_ID = "C012ABCDEFG" // starts with C
const val TEST_CHANNEL_NAME = "I_AM_TEST_CHANNEL"
const val TEST_TOKEN = "I_AM_TEST_TOKEN"
const val TEST_TEAM_ID = "T012ABCDEFG" // starts with T
const val TEST_TEAM_DOMAIN = "I_AM_TEST_TEAM_DOMAIN"
const val TEST_BOT_ID = "B012ABCDEFG"

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
    isPostEventPayload: Boolean = true,
    targetUserId: String? = null,
    appId: String = TEST_APP_ID,
    publisherId: String = TEST_USER_ID,
    channel: String = TEST_CHANNEL_ID,
    idempotencyKey: UUID = UUID.randomUUID(),
) = SendSlackMessageEvent(
    idempotencyKey = idempotencyKey,
    payload =
        if (isPostEventPayload) {
            createPostEventPayloadContents(
                appId = appId,
                publisherId = publisherId,
                channel = channel,
                targetUserId = targetUserId,
                commandDetailType = commandDetailType,
            )
        } else {
            createActionEventPayloadContents(
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
