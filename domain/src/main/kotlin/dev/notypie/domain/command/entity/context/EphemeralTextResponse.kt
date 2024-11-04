package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType

class EphemeralTextResponse(
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders,
    slackApiRequester: SlackApiRequester,
    private val textMessage: String,
): CommandContext(
    requestHeaders = requestHeaders,
    slackApiRequester = slackApiRequester,
    commandBasicInfo = commandBasicInfo
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): SlackApiResponse =
        this.slackApiRequester.simpleEphemeralTextRequest(
            commandBasicInfo = this.commandBasicInfo,
            commandDetailType = this.commandDetailType,
            commandType = this.commandType,
            textMessage = this.textMessage
        )

}