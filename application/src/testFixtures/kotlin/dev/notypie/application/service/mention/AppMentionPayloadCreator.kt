package dev.notypie.application.service.mention

import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.TEST_BOT_TOKEN
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_TEAM_ID
import dev.notypie.domain.TEST_USER_ID

fun createAppMentionPayload(
    appId: String? = TEST_APP_ID,
    token: String = TEST_BOT_TOKEN,
    teamId: String = TEST_TEAM_ID,
    type: String = "event_callback",
    eventId: String = "Ev0001",
    eventTime: String = "1234567890",
    eventContext: String = "ctx",
    isExtSharedChannel: Boolean = false,
    userName: String = "testuser",
    channelName: String = "general",
    publisherId: String = TEST_USER_ID,
    channel: String = TEST_CHANNEL_ID,
    eventType: String = "app_mention",
    botId: String = "B001",
): Map<String, Any> =
    buildMap {
        appId?.let { put("api_app_id", it) }
        put("token", token)
        put("team_id", teamId)
        put("type", type)
        put("event_id", eventId)
        put("event_time", eventTime)
        put("event_context", eventContext)
        put("is_ext_shared_channel", isExtSharedChannel)
        put("user_name", userName)
        put("channel_name", channelName)
        put(
            "authorizations",
            listOf(
                mapOf(
                    "enterprise_id" to null,
                    "team_id" to teamId,
                    "user_id" to publisherId,
                    "is_bot" to true,
                    "is_enterprise_install" to false,
                ),
            ),
        )
        put(
            "event",
            mapOf(
                "type" to eventType,
                "user" to publisherId,
                "app_id" to (appId ?: TEST_APP_ID),
                "bot_id" to botId,
                "ts" to 1234567890.123,
                "team" to teamId,
                "channel" to channel,
                "event_ts" to 1234567890.123,
                "channel_type" to "channel",
                "bot_profile" to
                    mapOf(
                        "id" to botId,
                        "name" to "TestBot",
                        "deleted" to false,
                        "updated" to 1234567890L,
                        "app_id" to (appId ?: TEST_APP_ID),
                        "user_id" to publisherId,
                        "team_id" to teamId,
                        "icons" to
                            mapOf(
                                "image_36" to "https://example.com/36.png",
                                "image_48" to "https://example.com/48.png",
                                "image_72" to "https://example.com/72.png",
                            ),
                    ),
                "blocks" to emptyList<Any>(),
            ),
        )
    }
