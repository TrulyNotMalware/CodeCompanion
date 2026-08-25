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
import java.util.UUID

/**
 * Handles the `view_submission` payload from the add-participant modal opened by
 * [AddParticipantContext]. Routing tokens live in the modal's `private_metadata`: the parser copies
 * them into [InboundInteraction.idempotencyKey] (the meetingUid) and [InboundInteraction.routingExtras]
 * (the requesterId), the same comma-tokenized convention used by the other modal flows.
 *
 * The modal exposes a single multi-users select; its selected user ids arrive as a comma-joined
 * string located by [InboundFieldKeys.ADD_PARTICIPANT_USERS]. A malformed meetingUid or an empty
 * selection falls through to a
 * no-op success — the modal auto-closes on 200 OK and the repository still defends host-only +
 * capacity invariants downstream.
 */
internal class AddParticipantSubmissionContext(
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT

    override fun handleInteraction(interaction: InboundInteraction): CommandOutput {
        val s =
            interaction.submission as? InboundSubmission.AddParticipant
                ?: return successOutput()
        val meetingUid =
            runCatching { UUID.fromString(s.meetingUidRaw) }
                .getOrElse { return successOutput() }
        val requesterId = s.requesterId.ifBlank { interaction.actor.id }
        val participantUserIds =
            s.participantUserIdsRaw
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        if (participantUserIds.isEmpty()) return successOutput()

        addIntent(
            CommandIntent.AddParticipant(
                meetingUid = meetingUid,
                requesterId = requesterId,
                participantUserIds = participantUserIds,
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
