package dev.notypie.domain.command.entity.context

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.intent.IntentQueue

/**
 * Base for `view_submission` leaf contexts. The routing seam (SubmissionRouter) parses the raw
 * variant BEFORE constructing the leaf, so a leaf holds a non-null [model] and only translates it
 * into effects in [accept] — no casts, no nulls, no interpretation of the envelope. Rejection is
 * not representable here; it routes to [IgnoredSubmissionContext] instead.
 */
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

/**
 * Terminal for a submission route that produced nothing to execute: the SUBMIT-typed interaction
 * carried no submission payload, or the payload was rejected by its parser. Returns plain success
 * (the modal must close on 200) and queues no effects. Deliberately NOT the [ReactionContext]
 * default handler, which would queue a replacement message.
 */
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
