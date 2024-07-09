package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.CommandType
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.CommandContext
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.SlackRequestHandler
import dev.notypie.domain.command.dto.response.SlackApiResponse

class SlackTextResponseContext(
    private val text: String,

    channel: String,
    appToken: String,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    slackApiRequester: SlackApiRequester,
): CommandContext(
    channel = channel,
    appToken = appToken,
    slackApiRequester = slackApiRequester,
    requestHeaders = requestHeaders,
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE

    override fun runCommand(): SlackApiResponse {
        return this.slackApiRequester.simpleTextRequest(
            headLineText = "Simple Text Response",
            channel=this.channel, simpleString = this.text)
    }
}