package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.*

internal class SlackNoticeContext(
    val users: Queue<String>,
    val commands: Queue<String>,

    commandBasicInfo: CommandBasicInfo,
    slackEventBuilder: SlackEventBuilder,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    events: EventQueue<CommandEvent<EventPayload>>
): CommandContext(
    slackEventBuilder = slackEventBuilder,
    requestHeaders = requestHeaders,
    commandBasicInfo = commandBasicInfo,
    events = events
){
    private val responseText: String = this.commands.joinToString { " " }
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun parseCommandDetailType() = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): CommandOutput {
        val event = this.slackEventBuilder.simpleTextRequest(
            headLineText = "Notice!",
            simpleString = this.createResponseText(),
            commandType = this.commandType,
            commandBasicInfo = this.commandBasicInfo, commandDetailType = this.commandDetailType
        )
        this.addNewEvent(commandEvent = event)
        return CommandOutput.success(payload = event.payload)
    }


    private fun createResponseText(): String {
        val userMentions = this.users.joinToString(" ") { user -> "<@$user>" }
        return "[Notice] $userMentions $responseText"
    }
}