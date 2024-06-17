package dev.notypie.domain.command.dto

import dev.notypie.domain.command.SlackCommandType

data class SlackCommandData(
    val slackCommandType: SlackCommandType,
    val rawHeader: SlackRequestHeaders,
    val rawBody: Map<String, Any>
)