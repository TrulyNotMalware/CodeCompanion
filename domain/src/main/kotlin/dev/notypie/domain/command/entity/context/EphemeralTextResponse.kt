package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload

internal class EphemeralTextResponse(
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders,
    slackEventBuilder: SlackEventBuilder,
    events: EventQueue<CommandEvent<EventPayload>>,
    private val textMessage: String,
): CommandContext(
    requestHeaders = requestHeaders,
    slackEventBuilder = slackEventBuilder,
    commandBasicInfo = commandBasicInfo,
    events = events
) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(): CommandOutput{
        val event = this.slackEventBuilder.simpleEphemeralTextRequest(
            commandBasicInfo = this.commandBasicInfo,
            commandDetailType = this.commandDetailType,
            commandType = this.commandType,
            textMessage = this.textMessage
        )
        this.addNewEvent(commandEvent = event)
        return CommandOutput.success(payload = event.payload)
    }
}