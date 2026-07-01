package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.ModalOpenHandle
import dev.notypie.domain.command.outbound.OutboundMessage
import java.util.UUID

internal class RescheduleMeetingContext(
    commandBasicInfo: CommandBasicInfo,
    requestHeaders: SlackRequestHeaders = SlackRequestHeaders(),
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        requestHeaders = requestHeaders,
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.RESCHEDULE_MEETING

    /**
     * Reschedule button carries `<listIdempotencyKey>,RESCHEDULE_MEETING,<meetingUid>`; the meetingUid
     * surfaces as the first routing extra. Emits [OutboundMessage.OpenModal] so the stager opens the
     * modal before the trigger_id expires. Missing extras or a blank trigger_id fall through to a no-op.
     */
    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {
        val meetingUid =
            interactionPayload.routingExtras
                .firstOrNull()
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return successOutput()
        addOutbound(
            OutboundMessage.OpenModal(
                handle = ModalOpenHandle(raw = interactionPayload.triggerId),
                form =
                    ModalForm.Reschedule(
                        meetingUid = meetingUid,
                        requesterId = interactionPayload.user.id,
                        channel = ConversationTarget(id = interactionPayload.channel.id),
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
