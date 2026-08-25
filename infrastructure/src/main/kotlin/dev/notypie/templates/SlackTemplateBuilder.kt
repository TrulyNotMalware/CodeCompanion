package dev.notypie.templates

import dev.notypie.domain.command.dto.modals.*
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.TopicOption
import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.domain.standup.dto.RoutineMemberDto
import dev.notypie.domain.standup.dto.StandupAnswerDto
import dev.notypie.templates.dto.LayoutBlocks
import dev.notypie.templates.dto.TimeScheduleAlertContents
import java.time.LocalDate
import java.time.LocalDateTime
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
     *   `"<meetingIdempotencyKey>,MEETING_DECLINE_REASON,<participantUserId>,<noticeChannel>,<noticeMessageTs>"`.
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

    /**
     * Builds the full Slack `view` payload JSON for the reschedule modal opened when a host
     * clicks Reschedule on `/meetup list`. Returns a serialized JSON string for the same reason
     * as [declineReasonModalViewJson] — the modal envelope carries top-level fields that aren't
     * expressible as a block list.
     *
     * The modal exposes a DATE_PICKER + TIME_PICKER pair pre-filled from [currentStartAt].
     * `private_metadata` uses the shared comma-tokenized routing format:
     *   `"<meetingUid>,MEETING_RESCHEDULE_SUBMIT,<requesterId>"`.
     * The view_submission parser surfaces requesterId as `routingExtras[0]` for
     * [dev.notypie.domain.command.entity.context.form.RescheduleMeetingSubmissionContext].
     */
    fun rescheduleMeetingModalViewJson(
        meetingUid: UUID,
        currentStartAt: LocalDateTime,
        requesterId: String,
        channel: String,
    ): String

    /**
     * Builds the full Slack `view` payload JSON for the add-participant modal opened when a host
     * clicks "Add participant" on `/meetup list`. Exposes a single multi-users select.
     * `private_metadata` uses the shared comma-tokenized routing format:
     *   `"<meetingUid>,MEETING_ADD_PARTICIPANT_SUBMIT,<requesterId>"`.
     * The view_submission parser surfaces requesterId as `routingExtras[0]` for
     * [dev.notypie.domain.command.entity.context.form.AddParticipantSubmissionContext].
     */
    fun addParticipantModalViewJson(meetingUid: UUID, requesterId: String, channel: String): String

    fun standupModalViewJson(
        routineName: String,
        sessionDate: LocalDate,
        sessionUid: UUID,
        userId: String,
        noticeChannel: String,
        noticeMessageTs: String,
        questions: List<String>,
    ): String

    /**
     * Builds the full Slack `view` payload JSON for the `/standup setup` modal. Returns a
     * serialized JSON string for the same reason as [declineReasonModalViewJson] — the modal
     * envelope carries top-level fields that aren't expressible as a block list.
     *
     * `private_metadata` uses the shared comma-tokenized routing format:
     *   `"<idempotencyKey>,STANDUP_SETUP_SUBMIT,<creatorId>,<commandChannel>"`.
     * The view_submission parser surfaces creatorId/commandChannel as `routingExtras[0..1]`
     * for [dev.notypie.domain.command.entity.context.form.StandupSetupSubmissionContext].
     */
    fun standupSetupModalViewJson(idempotencyKey: UUID, creatorId: String, commandChannel: String): String

    /**
     * Builds the `/subscribe` modal `view` JSON: a single multi-select rendered from [topics] (option
     * value = topic key). `private_metadata` uses the shared comma-tokenized routing format
     * `"<idempotencyKey>,CVE_SUBSCRIBE_SUBMIT"`; the submission carries no routing extras because the
     * subscriber is the submitting actor.
     */
    fun cveSubscribeModalViewJson(idempotencyKey: UUID, topics: List<TopicOption>): String

    /** Builds the `/unsubscribe` modal `view` JSON; mirrors [cveSubscribeModalViewJson]. */
    fun cveUnsubscribeModalViewJson(idempotencyKey: UUID, topics: List<TopicOption>): String

    fun standupSummaryTemplate(
        routineName: String,
        sessionDate: LocalDate,
        members: List<RoutineMemberDto>,
        answers: List<StandupAnswerDto>,
        questions: List<String>,
    ): LayoutBlocks
}
