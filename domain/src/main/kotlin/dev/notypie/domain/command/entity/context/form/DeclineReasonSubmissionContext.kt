package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.context.ReactionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import java.util.UUID

/**
 * Handles the `view_submission` payload generated when a participant picks a decline reason
 * from the modal opened by [MeetingApprovalResponseContext.handleDecline]. The routing tokens
 * for this context live in the modal's `private_metadata` — the interaction parser already
 * copies them into [InteractionPayload.idempotencyKey] / [InteractionPayload.routingExtras]
 * using the same comma-tokenized format as embedded-message-text routing.
 *
 * Note: view_submission carries no channel/response_url; the persistence path is the sole
 * side effect. The user's acknowledgement is the modal's auto-close on 200 OK.
 */
internal class DeclineReasonSubmissionContext(
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

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.DECLINE_REASON_MODAL

    override fun handleInteraction(interactionPayload: InteractionPayload): CommandOutput {
        val meetingIdempotencyKey =
            runCatching { UUID.fromString(interactionPayload.idempotencyKey) }
                .getOrElse {
                    // Malformed private_metadata — can't correlate to a meeting, so we skip
                    // persistence. The modal already auto-closes on 200 OK so the user sees
                    // no error; logs will carry the parse failure via upstream handlers.
                    return CommandOutput.success(
                        basicInfo = commandBasicInfo,
                        commandType = commandType,
                        commandDetailType = commandDetailType,
                    )
                }
        val participantUserId =
            interactionPayload.routingExtras
                .firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: interactionPayload.user.id
        val noticeChannel = interactionPayload.routingExtras.getOrNull(1).orEmpty()
        val noticeMessageTs = interactionPayload.routingExtras.getOrNull(2).orEmpty()
        val absentReason = extractSelectedReason(payload = interactionPayload)

        addIntent(
            CommandIntent.MeetingAttendanceUpdate(
                meetingIdempotencyKey = meetingIdempotencyKey,
                participantUserId = participantUserId,
                isAttending = false,
                absentReason = absentReason,
            ),
        )
        // chat.update the original notice so the user can't click Accept/Deny on a stale
        // message after submitting a reason. Skipped when the private_metadata carries no
        // channel/ts (synthesized test payloads, or legacy notices sent before Wave 2).
        if (noticeChannel.isNotBlank() && noticeMessageTs.isNotBlank()) {
            addIntent(
                CommandIntent.UpdateNoticeMessage(
                    channel = noticeChannel,
                    messageTs = noticeMessageTs,
                    markdownText = buildDeclineSummary(reason = absentReason),
                ),
            )
        }
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }

    private fun buildDeclineSummary(reason: RejectReason): String =
        "You declined the meeting — *Reason:* ${reason.showMessage}"

    /**
     * Parses the dropdown selection into a [RejectReason]. Unknown or blank values fall through
     * to [RejectReason.OTHER] rather than throwing — a malformed submission should still
     * register the user's Deny intent.
     */
    private fun extractSelectedReason(payload: InteractionPayload): RejectReason {
        val selected =
            payload.states
                .firstOrNull { it.type == ActionElementTypes.STATIC_SELECT }
                ?.selectedValue
                .orEmpty()
        return runCatching { RejectReason.valueOf(selected) }
            .getOrDefault(RejectReason.OTHER)
            .takeIf { it != RejectReason.ATTENDING } ?: RejectReason.OTHER
    }
}
