package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue

internal abstract class ReactionContext<T : SubCommandDefinition>(
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<T>,
    intents: IntentQueue,
) : CommandContext<T>(
        commandBasicInfo = commandBasicInfo,
        requestHeaders = requestHeaders,
        intents = intents,
        subCommand = subCommand,
    ) {
    protected fun interactionSuccessResponse(
        responseUrl: String,
        mkdMessage: String = "Successfully processed.",
    ): CommandOutput {
        addIntent(CommandIntent.ReplaceMessage(markdownText = mkdMessage, responseUrl = responseUrl))
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }

    protected fun interactionSuccessResponse(
        responseUrl: String,
        mkdMessage: String = "Successfully processed.",
        results: CommandOutput,
    ): CommandOutput {
        addIntent(CommandIntent.ReplaceMessage(markdownText = mkdMessage, responseUrl = responseUrl))
        return results
    }

    internal open fun runCommand(commandDetailType: CommandDetailType): CommandOutput = CommandOutput.empty()

    internal open fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput =
        interactionSuccessResponse(responseUrl = interactionPayload.responseUrl)
}

internal abstract class ResponseContext(
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    val isOk: Boolean = false,
    intents: IntentQueue,
) : CommandContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        requestHeaders = requestHeaders,
        intents = intents,
        subCommand = subCommand,
    ) {
    internal open fun runCommand(commandDetailType: CommandDetailType): CommandOutput = CommandOutput.empty()

    final override fun runCommand(): CommandOutput = runCommand(commandDetailType = commandDetailType)
}
