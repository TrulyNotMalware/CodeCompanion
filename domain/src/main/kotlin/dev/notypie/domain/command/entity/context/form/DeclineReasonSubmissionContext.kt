package dev.notypie.domain.command.entity.context.form

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.SubmissionContext
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.intent.IntentQueue
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.OutboundMessage

internal class DeclineReasonSubmissionContext(
    commandBasicInfo: CommandBasicInfo,
    intents: IntentQueue,
    model: DeclineReasonParsed,
) : SubmissionContext<DeclineReasonParsed>(
        commandBasicInfo = commandBasicInfo,
        intents = intents,
        model = model,
    ) {
    override fun parseCommandDetailType(): CommandDetailType = CommandDetailType.MEETING_DECLINE_REASON

    override fun accept(model: DeclineReasonParsed) {
        addIntent(
            CommandIntent.MeetingAttendanceUpdate(
                meetingIdempotencyKey = model.meetingIdempotencyKey,
                participantUserId = model.participantUserId,
                isAttending = false,
                absentReason = model.reason,
                absentReasonDetail = model.reasonDetail,
            ),
        )
        when (val notice = model.notice) {
            is NoticeTarget.Update ->
                addOutbound(
                    OutboundMessage.UpdateMessage(
                        ref =
                            MessageRef(
                                conversation = ConversationTarget(id = notice.channel),
                                messageId = notice.messageTs,
                            ),
                        content =
                            MessageContent.Text(
                                headline = null,
                                markdown = model.noticeSummaryMarkdown(),
                            ),
                        detailType = CommandDetailType.MEETING_DECLINE_REASON,
                    ),
                )

            NoticeTarget.None -> Unit
        }
    }
}
