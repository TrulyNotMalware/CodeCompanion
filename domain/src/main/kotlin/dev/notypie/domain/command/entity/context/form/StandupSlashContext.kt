package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.entity.slash.StandupSubCommandDefinition
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.ModalOpenHandle
import dev.notypie.domain.command.outbound.OutboundMessage

internal class StandupSlashContext(
    commandBasicInfo: CommandBasicInfo,
    private val triggerHandle: String,
    subCommand: SubCommand<StandupSubCommandDefinition> =
        SubCommand.of(definition = StandupSubCommandDefinition.SETUP),
    intents: IntentQueue,
) : ReactionContext<StandupSubCommandDefinition>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    companion object {
        internal val STOP_USAGE_MESSAGE: String = "Usage: ${StandupSubCommandDefinition.STOP.usage}"
    }

    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.STANDUP_SETUP_REQUEST

    override fun runCommand(): CommandOutput = runCommand(commandDetailType = commandDetailType)

    override fun runCommand(commandDetailType: CommandDetailType): CommandOutput {
        when (subCommand.subCommandDefinition) {
            StandupSubCommandDefinition.NONE,
            StandupSubCommandDefinition.SETUP,
            -> openSetupModal()

            StandupSubCommandDefinition.LIST -> addIntent(intent = CommandIntent.ListStandupRoutines)

            StandupSubCommandDefinition.STOP -> {
                val routineName =
                    subCommand.options
                        .filter { option -> option.isNotBlank() }
                        .joinToString(separator = " ")
                        .trim()
                if (routineName.isBlank()) return stopUsageError(commandDetailType = commandDetailType)
                addIntent(intent = CommandIntent.StopStandupRoutine(routineName = routineName))
            }
        }
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }

    private fun openSetupModal() {
        addOutbound(
            message =
                OutboundMessage.OpenModal(
                    handle = ModalOpenHandle(raw = triggerHandle),
                    form =
                        ModalForm.StandupSetup(
                            creatorId = commandBasicInfo.publisherId,
                            commandChannel = ConversationTarget(id = commandBasicInfo.channel),
                        ),
                ),
        )
    }

    private fun stopUsageError(commandDetailType: CommandDetailType): CommandOutput {
        addOutbound(
            message =
                OutboundMessage.Ephemeral(
                    target = ConversationTarget(id = commandBasicInfo.channel),
                    recipient = null,
                    content = MessageContent.Text(headline = null, markdown = STOP_USAGE_MESSAGE),
                ),
        )
        return CommandOutput.fail(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
            reason = STOP_USAGE_MESSAGE,
        )
    }
}
