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
    subCommand: SubCommand<NoSubCommands> = SubCommand.empty(),
    intents: IntentQueue,
) : ReactionContext<NoSubCommands>(
        commandBasicInfo = commandBasicInfo,
        subCommand = subCommand,
        intents = intents,
    ) {
    override fun parseCommandType(): CommandType = CommandType.PIPELINE

    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.MEETING_DECLINE_REASON

    override fun handleInteraction(interaction: InboundInteraction): CommandOutput {
        val s =
            interaction.submission as? InboundSubmission.DeclineReason
                ?: return successOutput()
        val meetingIdempotencyKey =
            runCatching { UUID.fromString(s.meetingIdempotencyKeyRaw) }
                .getOrElse {
                    // Malformed private_metadata — no meeting to correlate to, so skip persistence.
                    return successOutput()
                }
        val participantUserId = s.participantUserId.ifBlank { interaction.actor.id }
        val noticeChannel = s.noticeChannel
        val noticeMessageTs = s.noticeMessageTs
        val absentReason = parseReason(raw = s.reasonRaw)
        // Detail is only meaningful for OTHER; the "required when Other" rule is enforced upstream.
        val absentReasonDetail =
            s.detailRaw.trim().takeIf { absentReason == RejectReason.OTHER }

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
                    detailType = CommandDetailType.MEETING_DECLINE_REASON,
                ),
            )
        }
        return successOutput()
    }

    private fun successOutput() =
        CommandOutput.success(
            basicInfo = commandBasicInfo,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )

    private fun buildDeclineSummary(reason: RejectReason, detail: String?): String =
        buildString {
            append("You declined the meeting — *Reason:* ${reason.showMessage}")
            if (!detail.isNullOrBlank()) append(" — $detail")
        }

    /** Parses the dropdown value into a [RejectReason]; unknown/blank fall through to OTHER, never throwing. */
    private fun parseReason(raw: String): RejectReason =
        runCatching { RejectReason.valueOf(raw) }
            .getOrDefault(RejectReason.OTHER)
            .takeIf { it != RejectReason.ATTENDING } ?: RejectReason.OTHER
}
