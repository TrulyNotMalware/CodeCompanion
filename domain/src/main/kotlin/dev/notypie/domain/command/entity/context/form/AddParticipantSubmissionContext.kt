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
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import java.util.UUID

/**
 * Handles the `view_submission` payload from the add-participant modal opened by
 * [AddParticipantContext]. Routing tokens live in the modal's `private_metadata`: the parser copies
 * them into [InteractionPayload.idempotencyKey] (the meetingUid) and [InteractionPayload.routingExtras]
 * (the requesterId), the same comma-tokenized convention used by the other modal flows.
 *
 * The modal exposes a single multi-users select; its selected user ids arrive as a comma-joined
 * string located by [USERS_BLOCK_ID]. A malformed meetingUid or an empty selection falls through to a
 * no-op success — the modal auto-closes on 200 OK and the repository still defends host-only +
 * capacity invariants downstream.
 */
internal class AddParticipantSubmissionContext(
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

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.ADD_PARTICIPANT_SUBMIT

    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {
        val meetingUid =
            runCatching { UUID.fromString(interactionPayload.idempotencyKey) }
                .getOrElse { return successOutput() }
        val requesterId =
            interactionPayload.routingExtras
                .firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: interactionPayload.user.id
        // routingExtras[1] is the originating channel (ferried via private_metadata) so the host's
        // confirmation can be posted in-channel; a view_submission payload itself has no channel.
        val channel = interactionPayload.routingExtras.getOrNull(1).orEmpty()
        val participantUserIds = parseSelectedUserIds(interactionPayload = interactionPayload)
        if (participantUserIds.isEmpty()) return successOutput()

        addIntent(
            CommandIntent.AddParticipant(
                meetingUid = meetingUid,
                requesterId = requesterId,
                participantUserIds = participantUserIds,
                channel = channel,
            ),
        )
        return successOutput()
    }

    private fun parseSelectedUserIds(interactionPayload: InteractionPayload): List<String> =
        interactionPayload.states
            .firstOrNull { it.blockId == USERS_BLOCK_ID }
            ?.selectedValue
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun successOutput() =
        CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )

    companion object {
        // Mirrors AddParticipantModalIds.USERS_BLOCK_ID in the infrastructure layer. Kept as a plain
        // constant here so the domain module stays free of templating dependencies.
        const val USERS_BLOCK_ID: String = "add_participant_users"
    }
}
