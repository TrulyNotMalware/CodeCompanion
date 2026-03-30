package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload

internal abstract class ReactionContext<T : SubCommandDefinition>(
    slackEventBuilder: SlackEventBuilder,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    commandBasicInfo: CommandBasicInfo,
    events: EventQueue<CommandEvent<EventPayload>>,
    subCommand: SubCommand<T>,
) : CommandContext<T>(
        commandBasicInfo = commandBasicInfo,
        requestHeaders = requestHeaders,
        slackEventBuilder = slackEventBuilder,
        events = events,
        subCommand = subCommand,
    ) {
    protected fun interactionSuccessResponse(responseUrl: String, mkdMessage: String = "Successfully processed.") =
        replaceMessage(responseUrl = responseUrl, mkdMessage = mkdMessage)

    protected fun interactionSuccessResponse(
        responseUrl: String,
        mkdMessage: String = "Successfully processed.",
        results: CommandOutput,
    ): CommandOutput {
        replaceMessage(responseUrl = responseUrl, mkdMessage = mkdMessage)
        return results
    }

    private fun replaceMessage(responseUrl: String, mkdMessage: String) =
        ReplaceMessageContext(
            commandBasicInfo = commandBasicInfo,
            requestHeaders = requestHeaders,
            slackEventBuilder = slackEventBuilder,
            responseUrl = responseUrl,
            markdownMessage = mkdMessage,
            events = events,
        ).runCommand()

    internal open fun runCommand(commandDetailType: CommandDetailType): CommandOutput = CommandOutput.empty()

    internal open fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput =
        interactionSuccessResponse(responseUrl = interactionPayload.responseUrl)
}

internal abstract class ResponseContext(
    slackEventBuilder: SlackEventBuilder,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    commandBasicInfo: CommandBasicInfo,
    events: EventQueue<CommandEvent<EventPayload>>,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    val isOk: Boolean = false,
) : CommandContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        requestHeaders = requestHeaders,
        slackEventBuilder = slackEventBuilder,
        events = events,
        subCommand = subCommand,
    ) {
    internal open fun runCommand(commandDetailType: CommandDetailType): CommandOutput = CommandOutput.empty()

    final override fun runCommand(): CommandOutput = runCommand(commandDetailType = commandDetailType)
}
