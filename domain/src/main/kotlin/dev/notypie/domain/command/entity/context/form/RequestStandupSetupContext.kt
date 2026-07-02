package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.entity.slash.StandupSubCommandDefinition
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.ModalOpenHandle
import dev.notypie.domain.command.outbound.OutboundMessage

/**
 * Handles `/standup setup` by emitting [OutboundMessage.OpenModal]; the stager lifts it to a
 * synchronous `views.open` so [triggerId] is consumed within Slack's 3-second window.
 */
internal class RequestStandupSetupContext(
    commandBasicInfo: CommandBasicInfo,
    private val triggerId: String,
    subCommand: SubCommand<StandupSubCommandDefinition> =
        SubCommand.of(definition = StandupSubCommandDefinition.SETUP),
    intents: IntentQueue,
) : ReactionContext<StandupSubCommandDefinition>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.STANDUP_SETUP_REQUEST

    override fun runCommand(): CommandOutput = runCommand(commandDetailType = commandDetailType)

    override fun runCommand(commandDetailType: CommandDetailType): CommandOutput {
        addOutbound(
            OutboundMessage.OpenModal(
                handle = ModalOpenHandle(raw = triggerId),
                form =
                    ModalForm.StandupSetup(
                        creatorId = commandBasicInfo.publisherId,
                        commandChannel = ConversationTarget(id = commandBasicInfo.channel),
                    ),
            ),
        )
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }
}
