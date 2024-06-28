package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.CommandType
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.CommandContext
import dev.notypie.domain.command.SlackResponseBuilder
import dev.notypie.domain.command.SlackRequestHandler

class SlackTextResponseContext(
    channel: String,
    appToken: String,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    responseBuilder: SlackResponseBuilder,
    requestHandler: SlackRequestHandler
): CommandContext(
    channel = channel,
    appToken = appToken,
    responseBuilder = responseBuilder,
    requestHeaders = requestHeaders,
    requestHandler = requestHandler
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE

}