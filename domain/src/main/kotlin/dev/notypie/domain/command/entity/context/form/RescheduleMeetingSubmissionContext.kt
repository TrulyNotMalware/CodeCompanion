package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.inbound.InboundFieldKind
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Handles the `view_submission` payload from the reschedule modal opened by
 * [RescheduleMeetingContext]. The routing tokens live in the modal's `private_metadata` —
 * the interaction parser copies them into [InboundInteraction.idempotencyKey] (the meetingUid)
 * and [InboundInteraction.routingExtras] (the requesterId) using the same comma-tokenized
 * format as the other modal flows.
 *
 * The modal exposes a single DATE_PICKER + TIME_PICKER pair; we combine them into the new
 * start datetime. A malformed meetingUid or a missing/unparseable date/time falls through to
 * a no-op success — the modal already auto-closes on 200 OK, and the repository WHERE clause
 * still defends against a bogus uid downstream.
 */
internal class RescheduleMeetingSubmissionContext(
    commandBasicInfo: CommandBasicInfo,
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.RESCHEDULE_MEETING_SUBMIT

    override fun handleInteraction(interaction: InboundInteraction): CommandOutput {
        val meetingUid =
            runCatching { UUID.fromString(interaction.idempotencyKey) }
                .getOrElse { return successOutput() }
        val requesterId =
            interaction.routingExtras
                .firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: interaction.actor.id
        val newStartAt =
            parseNewStartAt(interaction = interaction)
                ?: return successOutput()

        addIntent(
            CommandIntent.RescheduleMeeting(
                meetingUid = meetingUid,
                requesterId = requesterId,
                newStartAt = newStartAt,
            ),
        )
        return successOutput()
    }

    /**
     * Combines the modal's DATE_PICKER + TIME_PICKER selections into a [LocalDateTime]. Returns
     * null when either selection is blank or fails to parse, so a half-filled submission is a
     * no-op rather than a 500.
     */
    private fun parseNewStartAt(interaction: InboundInteraction): LocalDateTime? {
        val dateString =
            interaction.form
                .all(kind = InboundFieldKind.DATE)
                .firstOrNull { it.rawValue.isNotBlank() }
                ?.rawValue
                ?: return null
        val timeString =
            interaction.form
                .all(kind = InboundFieldKind.TIME)
                .firstOrNull { it.rawValue.isNotBlank() }
                ?.rawValue
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
