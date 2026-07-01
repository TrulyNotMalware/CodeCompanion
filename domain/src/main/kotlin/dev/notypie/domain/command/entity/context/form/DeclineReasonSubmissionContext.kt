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
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.meet.entity.RejectReason
import java.util.UUID

/**
 * Handles the `view_submission` for the decline-reason modal opened by
 * [MeetingApprovalResponseContext.handleDecline]; routing tokens ride in `private_metadata`.
 * view_submission carries no channel/response_url, so persistence is the sole side effect and
 * the modal's auto-close on 200 OK is the acknowledgement.
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
                    // Malformed private_metadata — no meeting to correlate to, so skip persistence.
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
        // Detail is only meaningful for OTHER; the "required when Other" rule is enforced upstream.
        val absentReasonDetail =
            extractDetail(payload = interactionPayload).takeIf { absentReason == RejectReason.OTHER }

        addIntent(
            CommandIntent.MeetingAttendanceUpdate(
                meetingIdempotencyKey = meetingIdempotencyKey,
                participantUserId = participantUserId,
                isAttending = false,
                absentReason = absentReason,
                absentReasonDetail = absentReasonDetail,
            ),
        )
        // chat.update the original notice so Accept/Deny can't be clicked on a stale message.
        if (noticeChannel.isNotBlank() && noticeMessageTs.isNotBlank()) {
            addOutbound(
                OutboundMessage.UpdateMessage(
                    ref =
                        MessageRef(
                            conversation = ConversationTarget(id = noticeChannel),
                            messageId = noticeMessageTs,
                        ),
                    content =
                        MessageContent.Text(
                            headline = null,
                            markdown = buildDeclineSummary(reason = absentReason, detail = absentReasonDetail),
                        ),
                    detailType = CommandDetailType.DECLINE_REASON_MODAL,
                ),
            )
        }
        return CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )
    }

    private fun buildDeclineSummary(reason: RejectReason, detail: String?): String =
        buildString {
            append("You declined the meeting — *Reason:* ${reason.showMessage}")
            if (!detail.isNullOrBlank()) append(" — $detail")
        }

    /** Reads the single plain-text detail input; blank when left empty. */
    private fun extractDetail(payload: InteractionPayload): String =
        payload.states
            .firstOrNull { it.type == ActionElementTypes.PLAIN_TEXT_INPUT }
            ?.selectedValue
            .orEmpty()
            .trim()

    /** Parses the dropdown into a [RejectReason]; unknown/blank fall through to OTHER, never throwing. */
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
