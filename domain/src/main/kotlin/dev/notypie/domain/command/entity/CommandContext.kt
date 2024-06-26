package dev.notypie.domain.command.entity

import dev.notypie.domain.command.CommandType
import dev.notypie.domain.command.SlackRequestBuilder
import dev.notypie.domain.command.SlackRequestHandler
import dev.notypie.domain.command.dto.SlackRequestHeaders

abstract class CommandContext(
    val channel: String,
    val appToken: String,
    val tracking: Boolean = true,

    val requestHeaders: SlackRequestHeaders,
    val responseBuilder: SlackRequestBuilder,
    val requestHandler: SlackRequestHandler
) {
    val commandType: CommandType = this.parseCommandType()

    abstract fun parseCommandType(): CommandType
    open fun runCommand(){
        val requestBody = this.responseBuilder.buildRequestBody()
        this.requestHandler.sendToSlackServer(headers = this.requestHeaders, body = requestBody)
    }
}