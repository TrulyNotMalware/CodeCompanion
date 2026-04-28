package dev.notypie.templates

import dev.notypie.domain.command.dto.modals.*
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.templates.dto.LayoutBlocks
import dev.notypie.templates.dto.TimeScheduleAlertContents
import java.util.UUID

interface SlackTemplateBuilder {
    fun onlyTextTemplate(message: String, isMarkDown: Boolean): LayoutBlocks

    fun simpleTextResponseTemplate(headLineText: String, body: String, isMarkDown: Boolean): LayoutBlocks

    fun simpleScheduleNoticeTemplate(headLineText: String, timeScheduleInfo: TimeScheduleInfo): LayoutBlocks

    fun approvalTemplate(
        headLineText: String,
        approvalContents: ApprovalContents,
        idempotencyKey: UUID,
        commandDetailType: CommandDetailType,
    ): LayoutBlocks

    fun errorNoticeTemplate(headLineText: String, errorMessage: String, details: String?): LayoutBlocks

    fun requestApprovalFormTemplate(
        headLineText: String,
        selectionFields: List<SelectionContents>,
        approvalContents: ApprovalContents,
        approvalTargetUser: MultiUserSelectContents? = null,
        reasonInput: TextInputContents? = null,
    ): LayoutBlocks

    // Meets

    /**
     * Renders the `/meetup list` ephemeral. When [currentUserId] equals a meeting's creator
     * AND the meeting is not canceled, an inline Cancel button is appended after the section
     * so the host can cancel without typing the meeting id. [listIdempotencyKey] is embedded
     * in every cancel button's routing value so Slack-retry dedup applies to the click.
     */
    fun meetingListFormTemplate(
        meetings: List<MeetingDto>,
        currentUserId: String,
        listIdempotencyKey: UUID,
    ): LayoutBlocks

    fun requestMeetingFormTemplate(approvalContents: ApprovalContents): LayoutBlocks

    fun timeScheduleNoticeTemplate(
        timeScheduleInfo: TimeScheduleAlertContents,
        approvalContents: ApprovalContents,
    ): LayoutBlocks

    /**
     * Builds the full Slack `view` payload JSON used by `views.open` when a participant
     * clicks Deny on a meeting notice. Returns a serialized JSON string (not [LayoutBlocks])
     * because the modal envelope requires top-level `type`, `callback_id`, `title`, `submit`,
     * `close`, and `private_metadata` fields that are not expressible as a block list.
     *
     * `private_metadata` uses the same comma-tokenized format as the embedded-text routing
     * string used in DM notices:
     *   `"<meetingIdempotencyKey>,DECLINE_REASON_MODAL,<participantUserId>,<noticeChannel>,<noticeMessageTs>"`.
     * The view_submission parser then treats it with the same tokenization rules, surfacing
     * the extras as `routingExtras[0..2]` for [DeclineReasonSubmissionContext] to pick up.
     * `noticeChannel` + `noticeMessageTs` let the submission handler `chat.update` the
     * original Accept/Deny notice; empty values skip the update.
     */
    fun declineReasonModalViewJson(
        meetingTitle: String,
        meetingIdempotencyKey: UUID,
        participantUserId: String,
        noticeChannel: String,
        noticeMessageTs: String,
    ): String
}
