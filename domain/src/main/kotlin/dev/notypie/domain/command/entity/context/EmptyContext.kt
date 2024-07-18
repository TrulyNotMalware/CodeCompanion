package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.CommandType

class EmptyContext(
    channel: String,
    appToken: String,
    requestHeaders: SlackRequestHeaders,
    slackApiRequester: SlackApiRequester
): CommandContext(
    channel = channel,
    appToken = appToken,
    requestHeaders = requestHeaders,
    slackApiRequester = slackApiRequester
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
}