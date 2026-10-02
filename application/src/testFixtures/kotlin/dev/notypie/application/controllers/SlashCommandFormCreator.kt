package dev.notypie.application.controllers

import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_USER_ID

fun createSlashCommandForm(
    command: String = "/meetup",
    text: String = "",
    userId: String = TEST_USER_ID,
    channelId: String = TEST_CHANNEL_ID,
): Map<String, String> =
    mapOf(
        "token" to "verification-token",
        "team_id" to "T0001",
        "team_domain" to "team",
        "channel_id" to channelId,
        "channel_name" to "general",
        "api_app_id" to TEST_APP_ID,
        "is_enterprise_install" to "false",
        "user_id" to userId,
        "user_name" to "user",
        "command" to command,
        "text" to text,
        "response_url" to "https://hooks.slack.com/commands/T0001/1/token",
        "trigger_id" to "trigger-1",
    )
