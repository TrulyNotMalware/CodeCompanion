package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandType

abstract class CommandContext(
    val channel: String,
    val appToken: String,
    val tracking: Boolean = true,

    val requestHeaders: SlackRequestHeaders,
    val slackApiRequester: SlackApiRequester,
) {
    val commandType: CommandType = this.parseCommandType()

    internal abstract fun parseCommandType(): CommandType
    internal open fun runCommand(): SlackApiResponse{
        return this.slackApiRequester.simpleTextRequest(
            headLineText = "Hello Developer!",
            channel=this.channel, simpleString = "This is default response."
        )
    }
}