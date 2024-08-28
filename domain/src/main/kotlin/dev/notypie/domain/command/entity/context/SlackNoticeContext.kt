package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.response.SlackApiResponse
import java.util.*

internal class SlackNoticeContext(
    val users: Queue<String>,
    val commands: Queue<String>,

    channel: String,
    appToken: String,
    slackApiRequester: SlackApiRequester,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    idempotencyKey: String
): CommandContext(
    channel = channel,
    appToken = appToken,
    slackApiRequester = slackApiRequester,
    requestHeaders = requestHeaders,
    idempotencyKey = idempotencyKey
){
    private val responseText: String = this.commands.joinToString { " " }
    override fun parseCommandType(): CommandType = CommandType.SIMPLE

    override fun runCommand(): SlackApiResponse {
        return this.slackApiRequester.simpleTextRequest(
            headLineText = "Notice!",
            channel = this.channel, simpleString = this.createResponseText(),
            commandType = this.commandType,
            idempotencyKey = this.idempotencyKey
        )
    }


    private fun createResponseText(): String {
        val userMentions = this.users.joinToString(" ") { user -> "<@$user>" }
        return "[Notice] $userMentions $responseText"
    }
}