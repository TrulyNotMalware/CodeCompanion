package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import java.util.*

internal class SlackNoticeContext(
    val users: Queue<String>,
    val commands: Queue<String>,
    commandBasicInfo: CommandBasicInfo,
    slackEventBuilder: SlackEventBuilder,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    events: EventQueue<CommandEvent<EventPayload>>,
) : CommandContext<NoSubCommands>(
        slackEventBuilder = slackEventBuilder,
        requestHeaders = requestHeaders,
        commandBasicInfo = commandBasicInfo,
        events = events,
        subCommand = SubCommand.empty(),
    ) {
    private val responseText: String = commands.joinToString { " " }

    override fun parseCommandType(): CommandType = CommandType.SIMPLE

    override fun parseCommandDetailType() = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): CommandOutput {
        val event =
            slackEventBuilder.simpleTextRequest(
                headLineText = "Notice!",
                simpleString = createResponseText(),
                commandType = commandType,
                commandBasicInfo = commandBasicInfo,
                commandDetailType = commandDetailType,
            )
        addNewEvent(commandEvent = event)
        return CommandOutput.success(payload = event.payload, commandType = commandType)
    }

    private fun createResponseText(): String {
        val userMentions = users.joinToString(" ") { user -> "<@$user>" }
        return "[Notice] $userMentions $responseText"
    }
}
