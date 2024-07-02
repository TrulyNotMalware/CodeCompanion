package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.CommandType
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.CommandContext
import dev.notypie.domain.command.SlackResponseBuilder
import dev.notypie.domain.command.SlackRequestHandler
import dev.notypie.domain.command.dto.SlackEventContents
import dev.notypie.domain.command.dto.response.SlackApiResponse
import java.util.*

class SlackNoticeContext(
    val users: Queue<String>,
    val commands: Queue<String>,

    channel: String,
    appToken: String,
    responseBuilder: SlackResponseBuilder,
    requestHandler: SlackRequestHandler,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
): CommandContext(
    channel = channel,
    appToken = appToken,
    responseBuilder = responseBuilder,
    requestHandler = requestHandler,
    requestHeaders = requestHeaders,
){
    private val responseText: String = this.commands.joinToString { "" }
    override fun parseCommandType(): CommandType = CommandType.SIMPLE

    override fun runCommand(): SlackApiResponse {
        val eventContents: SlackEventContents = this.responseBuilder.buildRequestBody(
            channel = this.channel, simpleString = this.createResponseText()
        )
        val slackApiResponse = this.requestHandler.sendToSlackServer(headers = this.requestHeaders, body = eventContents)
        return slackApiResponse
    }


    private fun createResponseText(): String {
        val userMentions = this.users.joinToString(" ") { user -> "<@$user>" }
        return "[Notice] $userMentions $responseText"
    }
}