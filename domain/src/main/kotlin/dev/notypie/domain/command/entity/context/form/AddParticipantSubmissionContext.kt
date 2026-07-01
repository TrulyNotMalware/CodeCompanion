package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.inbound.InboundInteraction
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
 * string located by [USERS_BLOCK_ID]. A malformed meetingUid or an empty selection falls through to a
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

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.ADD_PARTICIPANT_SUBMIT

    override fun handleInteraction(interaction: InboundInteraction): CommandOutput {
        val meetingUid =
            runCatching { UUID.fromString(interaction.idempotencyKey) }
                .getOrElse { return successOutput() }
        val requesterId =
            interaction.routingExtras
                .firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: interaction.actor.id
        val participantUserIds = parseSelectedUserIds(interaction = interaction)
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

    private fun parseSelectedUserIds(interaction: InboundInteraction): List<String> =
        interaction.form
            .value(key = USERS_BLOCK_ID)
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
