package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.entity.slash.StandupSubCommandDefinition
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue

/**
 * Handles the `/standup setup` slash invocation by emitting [CommandIntent.OpenStandupSetupModal]
 * — the resolver lifts this to a synchronous `views.open` so the [triggerId] is consumed within
 * Slack's 3-second window. Mirrors [RequestMeetingContext]'s open-the-form path.
 */
internal class RequestStandupSetupContext(
    commandBasicInfo: CommandBasicInfo,
    private val triggerId: String,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    subCommand: SubCommand<StandupSubCommandDefinition> =
        SubCommand.of(definition = StandupSubCommandDefinition.SETUP),
    intents: IntentQueue,
) : ReactionContext<StandupSubCommandDefinition>(
        requestHeaders = requestHeaders,
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.STANDUP_SETUP_FORM

    override fun runCommand(): CommandOutput = runCommand(commandDetailType = commandDetailType)

    override fun runCommand(commandDetailType: CommandDetailType): CommandOutput {
        addIntent(
            CommandIntent.OpenStandupSetupModal(
                triggerId = triggerId,
                creatorId = commandBasicInfo.publisherId,
                commandChannel = commandBasicInfo.channel,
            ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}
