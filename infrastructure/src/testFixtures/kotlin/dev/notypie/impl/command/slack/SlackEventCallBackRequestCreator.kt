package dev.notypie.impl.command.slack

import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_MESSAGE_TS
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
    userId: String? = TEST_USER_ID,
    appId: String? = null,
    botId: String? = null,
    channel: String = TEST_CHANNEL_ID,
    teamId: String = TEST_TEAM_ID,
    blocks: List<Block> = emptyList(),
    ts: String = TEST_MESSAGE_TS,
    threadTs: String? = null,
) = EventCallbackData(
    type = type,
    userId = userId,
    appId = appId,
    botId = botId,
    ts = ts,
    threadTs = threadTs,
    team = teamId,
    channel = channel,
    eventTs = 1234567890.123,
    botProfile =
        botId?.let {
            BotProfile(
                id = it,
                name = "TestBot",
                deleted = false,
                updated = 1234567890L,
                appId = appId ?: TEST_APP_ID,
                userId = userId.orEmpty(),
                teamId = teamId,
                icons =
                    Icons(
                        imageSize36 = "https://example.com/36.png",
                        imageSize48 = "https://example.com/48.png",
                        imageSize72 = "https://example.com/72.png",
                    ),
            )
        },
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

fun createWorkflowAppMentionJson(botUserId: String): String =
    """
    {
      "token": "t", "team_id": "T1", "api_app_id": "A1", "type": "event_callback",
      "event_id": "Ev2", "event_time": "1", "is_ext_shared_channel": false, "event_context": "c",
      "authorizations": [{"enterprise_id": null, "team_id": "T1", "user_id": "$botUserId",
        "is_bot": true, "is_enterprise_install": false}],
      "event": {
        "type": "app_mention", "bot_id": "B_WORKFLOW", "app_id": "A_WF", "text": "<@$botUserId> help",
        "ts": "1712345678.000200", "team": "T1", "channel": "C1", "event_ts": 1712345678.0002
      }
    }
    """.trimIndent()

fun createUserElement(userId: String) = Element(type = "user", userId = userId)

fun createTextElement(text: String) = Element(type = "text", userId = null, text = PlainText(value = text))
