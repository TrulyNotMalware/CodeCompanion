package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.inbound.InboundSubmission
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue

/**
 * Parses the `/subscribe` modal `view_submission` into a [CommandIntent.CveSubscribe]. The subscriber
 * is the submitting actor; the topic keys are the multi-select values the infra mapper already split.
 * Resolving keys to topics and skipping unknown ones is the application service's job — this context
 * stays persistence-blind.
 */
internal class CveSubscribeSubmissionContext(
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.CVE_SUBSCRIBE_SUBMIT

    override fun handleInteraction(interaction: InboundInteraction): CommandOutput {
        val submission =
            interaction.submission as? InboundSubmission.CveSubscribe
                ?: return successOutput()
        addIntent(
            CommandIntent.CveSubscribe(
                userId = interaction.actor.id,
                topicKeys = submission.topicKeys,
            ),
        )
        return successOutput()
    }

    private fun successOutput() =
        CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
}

/**
 * Parses the `/unsubscribe` modal `view_submission` into a [CommandIntent.CveUnsubscribe]. Mirrors
 * [CveSubscribeSubmissionContext]; the application service resolves the keys against the user's
 * current subscriptions and deletes the matching rows.
 */
internal class CveUnsubscribeSubmissionContext(
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.CVE_UNSUBSCRIBE_SUBMIT

    override fun handleInteraction(interaction: InboundInteraction): CommandOutput {
        val submission =
            interaction.submission as? InboundSubmission.CveUnsubscribe
                ?: return successOutput()
        addIntent(
            CommandIntent.CveUnsubscribe(
                userId = interaction.actor.id,
                topicKeys = submission.topicKeys,
            ),
        )
        return successOutput()
    }

    private fun successOutput() =
        CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
}
