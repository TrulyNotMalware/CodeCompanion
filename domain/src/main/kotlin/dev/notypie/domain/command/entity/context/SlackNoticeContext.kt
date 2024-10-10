package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.CommandDetailType
import java.util.*

internal class SlackNoticeContext(
    val users: Queue<String>,
    val commands: Queue<String>,

    commandBasicInfo: CommandBasicInfo,
    slackApiRequester: SlackApiRequester,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
): CommandContext(
    slackApiRequester = slackApiRequester,
    requestHeaders = requestHeaders,
    commandBasicInfo = commandBasicInfo
){
    private val responseText: String = this.commands.joinToString { " " }
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun parseCommandDetailType() = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): SlackApiResponse {
        return this.slackApiRequester.simpleTextRequest(
            headLineText = "Notice!",
            channel = this.commandBasicInfo.channel, simpleString = this.createResponseText(),
            commandType = this.commandType,
            idempotencyKey = this.commandBasicInfo.idempotencyKey, commandDetailType = this.commandDetailType
        )
    }


    private fun createResponseText(): String {
        val userMentions = this.users.joinToString(" ") { user -> "<@$user>" }
        return "[Notice] $userMentions $responseText"
    }
}