package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.ResponseReplaceHandle

internal abstract class ReactionContext<T : SubCommandDefinition>(
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<T>,
    intents: IntentQueue,
) : CommandContext<T>(
        commandBasicInfo = commandBasicInfo,
        intents = intents,
        subCommand = subCommand,
    ) {
    protected fun interactionSuccessResponse(
        responseUrl: String,
        mkdMessage: String = "Successfully processed.",
    ): CommandOutput {
        addOutbound(
            OutboundMessage.ReplaceMessage(
                handle = ResponseReplaceHandle(raw = responseUrl),
                content = MessageContent.Text(headline = null, markdown = mkdMessage),
            ),
        )
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
        addOutbound(
            OutboundMessage.ReplaceMessage(
                handle = ResponseReplaceHandle(raw = responseUrl),
                content = MessageContent.Text(headline = null, markdown = mkdMessage),
            ),
        )
        return results
    }

    internal open fun runCommand(commandDetailType: CommandDetailType): CommandOutput = CommandOutput.empty()

    internal open fun handleInteraction(interaction: InboundInteraction): CommandOutput =
        interactionSuccessResponse(responseUrl = interaction.reply.raw)
}

internal abstract class ResponseContext(
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    val isOk: Boolean = false,
    intents: IntentQueue,
) : CommandContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        intents = intents,
        subCommand = subCommand,
    ) {
    internal open fun runCommand(commandDetailType: CommandDetailType): CommandOutput = CommandOutput.empty()

    final override fun runCommand(): CommandOutput = runCommand(commandDetailType = commandDetailType)
}
