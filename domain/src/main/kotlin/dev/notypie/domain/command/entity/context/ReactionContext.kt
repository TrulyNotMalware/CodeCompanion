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
import dev.notypie.domain.command.dto.withNewKey
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import kotlin.reflect.KClass

internal abstract class ReactionContext(
    slackEventBuilder: SlackEventBuilder,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    commandBasicInfo: CommandBasicInfo,
    events: EventQueue<CommandEvent<EventPayload>>,
    requiredSubCommandType: KClass<out SubCommandDefinition> = NoSubCommands::class,
    subCommand: SubCommand = SubCommand.empty(),
) : CommandContext(
        commandBasicInfo = commandBasicInfo.withNewKey(),
        requestHeaders = requestHeaders,
        slackEventBuilder = slackEventBuilder,
        events = events,
        requiredSubCommandType = requiredSubCommandType,
        subCommand = subCommand,
    ) {
    protected fun interactionSuccessResponse(
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

    protected fun interactionSuccessResponse(
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

    internal open fun runCommand(commandDetailType: CommandDetailType): CommandOutput = CommandOutput.empty()

    internal open fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput =
        interactionSuccessResponse(responseUrl = interactionPayload.responseUrl)
}

internal abstract class ResponseContext(
    slackEventBuilder: SlackEventBuilder,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    commandBasicInfo: CommandBasicInfo,
    events: EventQueue<CommandEvent<EventPayload>>,
    val isOk: Boolean = false,
) : CommandContext(
        commandBasicInfo = commandBasicInfo.withNewKey(),
        requestHeaders = requestHeaders,
        slackEventBuilder = slackEventBuilder,
        events = events,
    ) {
    internal open fun runCommand(commandDetailType: CommandDetailType): CommandOutput = CommandOutput.empty()

    final override fun runCommand(): CommandOutput = runCommand(commandDetailType = commandDetailType)
}
