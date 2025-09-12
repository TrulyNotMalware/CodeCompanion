package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload

internal abstract class CommandContext(
    val commandBasicInfo: CommandBasicInfo,
    val tracking: Boolean = true,
    val requestHeaders: SlackRequestHeaders,
    val slackEventBuilder: SlackEventBuilder,
    val subCommand: SubCommand = SubCommand(subCommandDefinition = NoSubCommands()),
    val events: EventQueue<CommandEvent<EventPayload>>,
) {
    val commandType: CommandType by lazy { parseCommandType() }
    val commandDetailType: CommandDetailType by lazy { parseCommandDetailType() }

    internal abstract fun parseCommandType(): CommandType

    internal abstract fun parseCommandDetailType(): CommandDetailType

    internal open fun runCommand(): CommandOutput = CommandOutput.empty()

    internal open fun runCommand(commandDetailType: CommandDetailType): CommandOutput = CommandOutput.empty()

    internal open fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput = CommandOutput.empty()

    internal fun createErrorResponse(errMessage: String): CommandOutput =
        EphemeralTextResponse(
            commandBasicInfo = commandBasicInfo,
            requestHeaders = requestHeaders,
            slackEventBuilder = slackEventBuilder,
            textMessage = errMessage,
            events = events,
        ).runCommand()

    internal fun createErrorResponse(errMessage: String, results: CommandOutput): CommandOutput {
        EphemeralTextResponse(
            commandBasicInfo = commandBasicInfo,
            requestHeaders = requestHeaders,
            slackEventBuilder = slackEventBuilder,
            textMessage = errMessage,
            events = events,
        ).runCommand()
        return results
    }

    internal fun interactionSuccessResponse(
        responseUrl: String,
        mkdMessage: String = "Successfully processed.",
    ): CommandOutput =
        ReplaceMessageContext(
            commandBasicInfo = commandBasicInfo,
            requestHeaders = requestHeaders,
            slackEventBuilder = slackEventBuilder,
            responseUrl = responseUrl,
            markdownMessage = mkdMessage,
            events = events,
            subCommand = subCommand,
        ).runCommand()

    internal fun interactionSuccessResponse(
        responseUrl: String,
        mkdMessage: String = "Successfully processed.",
        results: CommandOutput,
    ): CommandOutput {
        ReplaceMessageContext(
            commandBasicInfo = commandBasicInfo,
            requestHeaders = requestHeaders,
            slackEventBuilder = slackEventBuilder,
            responseUrl = responseUrl,
            markdownMessage = mkdMessage,
            events = events,
            subCommand = subCommand,
        ).runCommand()
        return results
    }

    internal fun addNewEvent(commandEvent: CommandEvent<EventPayload>) = events.offer(event = commandEvent)
}
