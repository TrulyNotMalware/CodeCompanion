package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload

internal abstract class CommandContext<T : SubCommandDefinition>(
    val commandBasicInfo: CommandBasicInfo,
    val tracking: Boolean = true,
    val requestHeaders: SlackRequestHeaders,
    val slackEventBuilder: SlackEventBuilder,
    val subCommand: SubCommand<T>,
    val events: EventQueue<CommandEvent<EventPayload>>,
) {
    val commandType: CommandType by lazy { parseCommandType() }
    val commandDetailType: CommandDetailType by lazy { parseCommandDetailType() }

    protected abstract fun parseCommandType(): CommandType

    protected abstract fun parseCommandDetailType(): CommandDetailType

    internal open fun runCommand(): CommandOutput = CommandOutput.empty()

    protected fun createErrorResponse(errMessage: String): CommandOutput =
        EphemeralTextResponseContext(
            commandBasicInfo = commandBasicInfo,
            requestHeaders = requestHeaders,
            slackEventBuilder = slackEventBuilder,
            textMessage = errMessage,
            events = events,
            isOk = false,
        ).runCommand(commandDetailType = commandDetailType)

    protected fun createErrorResponse(errMessage: String, results: CommandOutput): CommandOutput {
        EphemeralTextResponseContext(
            commandBasicInfo = commandBasicInfo,
            requestHeaders = requestHeaders,
            slackEventBuilder = slackEventBuilder,
            textMessage = errMessage,
            events = events,
            isOk = false,
        ).runCommand(commandDetailType = commandDetailType)
        return results
    }

    protected fun addNewEvent(commandEvent: CommandEvent<EventPayload>) = events.offer(event = commandEvent)
}
