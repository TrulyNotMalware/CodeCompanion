package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType

abstract class CommandContext(
    val commandBasicInfo: CommandBasicInfo,
    val tracking: Boolean = true,
    val requestHeaders: SlackRequestHeaders,
    val slackApiRequester: SlackApiRequester,
) {
    val commandType: CommandType = this.parseCommandType()
    val commandDetailType: CommandDetailType = this.parseCommandDetailType()

    internal abstract fun parseCommandType(): CommandType
    internal abstract fun parseCommandDetailType(): CommandDetailType
    internal open fun runCommand(): SlackApiResponse{
        return this.slackApiRequester.simpleTextRequest(
            headLineText = "Hello Developer!",
            channel = this.commandBasicInfo.channel, simpleString = "This is default response.",
            commandType = CommandType.SIMPLE,
            idempotencyKey = this.commandBasicInfo.idempotencyKey,
            commandDetailType = CommandDetailType.SIMPLE_TEXT
        )
    }
    internal open fun doWhenApproved():CommandContext = EmptyContext(
        commandBasicInfo = this.commandBasicInfo,
        requestHeaders = this.requestHeaders,
        slackApiRequester = this.slackApiRequester
    )
}