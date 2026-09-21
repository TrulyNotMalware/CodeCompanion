package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.intent.IntentQueue

internal abstract class SubmissionContext<M : Any>(
    commandBasicInfo: CommandBasicInfo,
    intents: IntentQueue,
    private val model: M,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = SubCommand.empty(),
        intents = intents,
    ) {
    final override fun parseCommandType(): CommandType = CommandType.PIPELINE

    protected abstract fun accept(model: M)

    final override fun handleInteraction(interaction: InboundInteraction): CommandOutput {
        accept(model)
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}

// Returns success so the modal closes (200); the default handler would wrongly queue a message.
internal class IgnoredSubmissionContext(
    commandBasicInfo: CommandBasicInfo,
    intents: IntentQueue,
    private val detailType: CommandDetailType,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = SubCommand.empty(),
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = detailType

    override fun handleInteraction(interaction: InboundInteraction): CommandOutput =
        CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
}
