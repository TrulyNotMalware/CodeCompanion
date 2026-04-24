package dev.notypie.domain.command

import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.TEST_BOT_ID
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_CHANNEL_NAME
import dev.notypie.domain.TEST_TEAM_ID
import dev.notypie.domain.TEST_TOKEN
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.TEST_USER_NAME
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.interactions.States
import dev.notypie.domain.command.dto.mention.Authorization
import dev.notypie.domain.command.dto.mention.Block
import dev.notypie.domain.command.dto.mention.BotProfile
import dev.notypie.domain.command.dto.mention.Element
import dev.notypie.domain.command.dto.mention.EventCallbackData
import dev.notypie.domain.command.dto.mention.Icons
import dev.notypie.domain.command.dto.mention.PlainText
import dev.notypie.domain.command.dto.mention.SlackEventCallBackRequest
import dev.notypie.domain.command.entity.CommandDetailType
import java.util.UUID

fun createSlackEventCallBackRequest(
    token: String = TEST_TOKEN,
    teamId: String = TEST_TEAM_ID,
    apiAppId: String = TEST_APP_ID,
    type: String = "event_callback",
    eventId: String = "Ev0001",
    eventTime: String = "1234567890",
    eventContext: String = "ctx",
    isExtSharedChannel: Boolean = false,
    event: EventCallbackData = createEventCallbackData(),
    authorizations: List<Authorization> = listOf(createAuthorization()),
) = SlackEventCallBackRequest(
    token = token,
    teamId = teamId,
    apiAppId = apiAppId,
    type = type,
    eventId = eventId,
    eventTime = eventTime,
    eventContext = eventContext,
    isExtSharedChannel = isExtSharedChannel,
    event = event,
    authorizations = authorizations,
)

fun createAuthorization(
    enterpriseId: String? = null,
    teamId: String = TEST_TEAM_ID,
    userId: String = TEST_USER_ID,
    isBot: Boolean = true,
    isEnterpriseInstall: Boolean = false,
) = Authorization(
    enterpriseId = enterpriseId,
    teamId = teamId,
    userId = userId,
    isBot = isBot,
    isEnterpriseInstall = isEnterpriseInstall,
)

fun createEventCallbackData(
    type: String = "app_mention",
    userId: String = TEST_USER_ID,
    appId: String = TEST_APP_ID,
    botId: String = TEST_BOT_ID,
    channel: String = TEST_CHANNEL_ID,
    teamId: String = TEST_TEAM_ID,
    blocks: List<Block> = emptyList(),
) = EventCallbackData(
    type = type,
    userId = userId,
    appId = appId,
    botId = botId,
    ts = 1234567890.123,
    team = teamId,
    channel = channel,
    eventTs = 1234567890.123,
    channelType = "channel",
    botProfile =
        BotProfile(
            id = botId,
            name = "TestBot",
            deleted = false,
            updated = 1234567890L,
            appId = appId,
            userId = userId,
            teamId = teamId,
            icons =
                Icons(
                    imageSize36 = "https://example.com/36.png",
                    imageSize48 = "https://example.com/48.png",
                    imageSize72 = "https://example.com/72.png",
                ),
        ),
    blocks = blocks,
)

fun createRichTextBlock(vararg elements: Element) =
    Block(
        type = "rich_text",
        blockId = "block_1",
        elements =
            listOf(
                Element(
                    type = "rich_text_section",
                    userId = null,
                    elements = elements.toList(),
                ),
            ),
    )

fun createUserElement(userId: String) = Element(type = "user", userId = userId)

fun createTextElement(text: String) = Element(type = "text", userId = null, text = PlainText(value = text))

fun createAppMentionSlackCommandData(
    appId: String = TEST_APP_ID,
    appToken: String = TEST_TOKEN,
    publisherId: String = TEST_USER_ID,
    publisherName: String = TEST_USER_NAME,
    channel: String = TEST_CHANNEL_ID,
    body: SlackEventCallBackRequest = createSlackEventCallBackRequest(),
) = SlackCommandData(
    appId = appId,
    appToken = appToken,
    publisherId = publisherId,
    publisherName = publisherName,
    channel = channel,
    channelName = "general",
    slackCommandType = SlackCommandType.APP_MENTION,
    rawHeader = SlackRequestHeaders(),
    rawBody = emptyMap(),
    body = body,
)

fun createInteractionSlackCommandData(
    commandDetailType: CommandDetailType = CommandDetailType.APPROVAL_FORM,
    currentAction: States = States(type = ActionElementTypes.APPLY_BUTTON, isSelected = true),
    states: List<States> = emptyList(),
    idempotencyKey: UUID = UUID.randomUUID(),
    appId: String = TEST_APP_ID,
    appToken: String = TEST_TOKEN,
    publisherId: String = TEST_USER_ID,
    publisherName: String = TEST_USER_NAME,
    channel: String = TEST_CHANNEL_ID,
) = SlackCommandData(
    appId = appId,
    appToken = appToken,
    publisherId = publisherId,
    publisherName = publisherName,
    channel = channel,
    channelName = "general",
    slackCommandType = SlackCommandType.INTERACTION_RESPONSE,
    rawHeader = SlackRequestHeaders(),
    rawBody = emptyMap(),
    body =
        createInteractionPayloadInput(
            commandDetailType = commandDetailType,
            currentAction = currentAction,
            states = states,
            idempotencyKey = idempotencyKey,
        ),
)

fun createInteractionResponseSlackCommandData(
    interactionPayload: InteractionPayload,
    slackCommandType: SlackCommandType = SlackCommandType.INTERACTION_RESPONSE,
    appId: String = TEST_APP_ID,
    appToken: String = TEST_TOKEN,
    publisherId: String = TEST_USER_ID,
    publisherName: String = TEST_USER_NAME,
    channel: String = TEST_CHANNEL_ID,
    channelName: String = TEST_CHANNEL_NAME,
) = SlackCommandData(
    appId = appId,
    appToken = appToken,
    publisherId = publisherId,
    publisherName = publisherName,
    channel = channel,
    channelName = channelName,
    slackCommandType = slackCommandType,
    rawHeader = SlackRequestHeaders(),
    rawBody = emptyMap(),
    body = interactionPayload,
)

fun createSlashCommandData(
    subCommands: List<String> = emptyList(),
    body: Any = "",
    appId: String = TEST_APP_ID,
    appToken: String = TEST_TOKEN,
    publisherId: String = TEST_USER_ID,
    publisherName: String = TEST_USER_NAME,
    channel: String = TEST_CHANNEL_ID,
    channelName: String = TEST_CHANNEL_NAME,
) = SlackCommandData(
    appId = appId,
    appToken = appToken,
    publisherId = publisherId,
    publisherName = publisherName,
    channel = channel,
    channelName = channelName,
    slackCommandType = SlackCommandType.SLASH,
    subCommands = subCommands,
    rawHeader = SlackRequestHeaders(),
    rawBody = emptyMap(),
    body = body,
)
