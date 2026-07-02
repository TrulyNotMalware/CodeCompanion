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

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.MEETING_RESCHEDULE_SUBMIT

    override fun handleInteraction(interaction: InboundInteraction): CommandOutput {
        val s =
            interaction.submission as? InboundSubmission.RescheduleMeeting
                ?: return successOutput()
        val meetingUid =
            runCatching { UUID.fromString(s.meetingUidRaw) }
                .getOrElse { return successOutput() }
        val requesterId = s.requesterId.ifBlank { interaction.actor.id }
        val newStartAt =
            parseNewStartAt(date = s.date, time = s.time)
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
     * Combines the modal's date + time selections into a [LocalDateTime]. Returns null when either
     * selection is blank or fails to parse, so a half-filled submission is a no-op rather than a 500.
     */
    private fun parseNewStartAt(date: String, time: String): LocalDateTime? {
        if (date.isBlank() || time.isBlank()) return null
        return runCatching {
            LocalDateTime.parse(
                "$date $time",
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
