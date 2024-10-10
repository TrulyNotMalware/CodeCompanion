package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandDetailType

internal class SlackTextResponseContext(
    private val text: String,
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    slackApiRequester: SlackApiRequester
): CommandContext(
    slackApiRequester = slackApiRequester,
    requestHeaders = requestHeaders,
    commandBasicInfo = commandBasicInfo
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun parseCommandDetailType() = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): SlackApiResponse {
        return this.slackApiRequester.simpleTextRequest(
            headLineText = "Simple Text Response",
            channel=this.commandBasicInfo.channel, simpleString = this.text,
            commandType = this.commandType, idempotencyKey = this.commandBasicInfo.idempotencyKey,
            commandDetailType = this.commandDetailType
        )
    }
}