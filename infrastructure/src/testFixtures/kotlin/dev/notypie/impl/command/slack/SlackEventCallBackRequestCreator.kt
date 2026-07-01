package dev.notypie.impl.command.slack

import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.TEST_BOT_ID
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_TEAM_ID
import dev.notypie.domain.TEST_TOKEN
import dev.notypie.domain.TEST_USER_ID

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
