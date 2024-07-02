package dev.notypie.domain.command.entity

import dev.notypie.domain.command.CommandType
import dev.notypie.domain.command.SlackResponseBuilder
import dev.notypie.domain.command.SlackRequestHandler
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.SlackApiResponse

abstract class CommandContext(
    val channel: String,
    val appToken: String,
    val tracking: Boolean = true,

    val requestHeaders: SlackRequestHeaders,
    val responseBuilder: SlackResponseBuilder,
    val requestHandler: SlackRequestHandler
) {
    val commandType: CommandType = this.parseCommandType()

    internal abstract fun parseCommandType(): CommandType
    internal open fun runCommand(): SlackApiResponse{
        val requestBody = this.responseBuilder.buildRequestBody(channel=this.channel, simpleString = "This is default response.")
        return this.requestHandler.sendToSlackServer(headers = this.requestHeaders, body = requestBody)
    }
}