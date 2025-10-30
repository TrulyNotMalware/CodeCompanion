package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload

internal class EphemeralTextResponseContext(
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders,
    slackEventBuilder: SlackEventBuilder,
    events: EventQueue<CommandEvent<EventPayload>>,
    isOk: Boolean = true,
    private val textMessage: String,
) : ResponseContext(
        requestHeaders = requestHeaders,
        slackEventBuilder = slackEventBuilder,
        commandBasicInfo = commandBasicInfo,
        events = events,
        isOk = isOk,
    ) {
    override fun parseCommandType(): CommandType = CommandType.SIMPLE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.SIMPLE_TEXT

    override fun runCommand(commandDetailType: CommandDetailType): CommandOutput {
        val event =
            slackEventBuilder.simpleEphemeralTextRequest(
                commandBasicInfo = commandBasicInfo,
                commandDetailType = commandDetailType,
                commandType = commandType,
                textMessage = textMessage,
            )
        addNewEvent(commandEvent = event)
        return if (isOk) {
            CommandOutput.success(payload = event.payload, commandType = commandType)
        } else {
            CommandOutput.fail(event = event.payload, reason = textMessage)
        }
    }
}
