package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.ModalOpenHandle
import dev.notypie.domain.command.outbound.OutboundMessage
import java.util.UUID

/**
 * "Add participant" button carries `<listIdempotencyKey>,ADD_PARTICIPANT,<meetingUid>`; the meetingUid
 * surfaces as the first routing extra. Emits [OutboundMessage.OpenModal] so the stager opens the modal
 * before the trigger_id expires, mirroring [RescheduleMeetingContext].
 */
internal class AddParticipantContext(
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.ADD_PARTICIPANT

    override fun handleInteraction(interaction: InboundInteraction): CommandOutput {
        val meetingUid =
            interaction.routingExtras
                .firstOrNull()
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return successOutput()
        addOutbound(
            OutboundMessage.OpenModal(
                handle = ModalOpenHandle(raw = interaction.trigger.raw),
                form =
                    ModalForm.AddParticipant(
                        meetingUid = meetingUid,
                        requesterId = interaction.actor.id,
                        channel = ConversationTarget(id = interaction.channelId),
                    ),
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
