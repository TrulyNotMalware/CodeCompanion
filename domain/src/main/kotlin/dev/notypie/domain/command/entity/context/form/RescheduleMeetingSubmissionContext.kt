package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Handles the `view_submission` payload from the reschedule modal opened by
 * [RescheduleMeetingContext]. The routing tokens live in the modal's `private_metadata` —
 * the interaction parser copies them into [InteractionPayload.idempotencyKey] (the meetingUid)
 * and [InteractionPayload.routingExtras] (the requesterId) using the same comma-tokenized
 * format as the other modal flows.
 *
 * The modal exposes a single DATE_PICKER + TIME_PICKER pair; we combine them into the new
 * start datetime. A malformed meetingUid or a missing/unparseable date/time falls through to
 * a no-op success — the modal already auto-closes on 200 OK, and the repository WHERE clause
 * still defends against a bogus uid downstream.
 */
internal class RescheduleMeetingSubmissionContext(
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

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.RESCHEDULE_MEETING_SUBMIT

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
        val newStartAt =
            parseNewStartAt(interactionPayload = interactionPayload)
                ?: return successOutput()

        addIntent(
            CommandIntent.RescheduleMeeting(
                meetingUid = meetingUid,
                requesterId = requesterId,
                newStartAt = newStartAt,
                channel = channel,
            ),
        )
        return successOutput()
    }

    /**
     * Combines the modal's DATE_PICKER + TIME_PICKER selections into a [LocalDateTime]. Returns
     * null when either selection is blank or fails to parse, so a half-filled submission is a
     * no-op rather than a 500.
     */
    private fun parseNewStartAt(interactionPayload: InteractionPayload): LocalDateTime? {
        val dateString =
            interactionPayload.states
                .firstOrNull { it.type == ActionElementTypes.DATE_PICKER && it.selectedValue.isNotBlank() }
                ?.selectedValue
                ?: return null
        val timeString =
            interactionPayload.states
                .firstOrNull { it.type == ActionElementTypes.TIME_PICKER && it.selectedValue.isNotBlank() }
                ?.selectedValue
                ?: return null
        return runCatching {
            LocalDateTime.parse(
                "$dateString $timeString",
                DateTimeFormatter.ofPattern("$DATE_PATTERN $TIME_PATTERN"),
            )
        }.getOrNull()
    }

    private fun successOutput() =
        CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )

    companion object {
        internal const val DATE_PATTERN = "yyyy-MM-dd"
        internal const val TIME_PATTERN = "HH:mm"
    }
}
