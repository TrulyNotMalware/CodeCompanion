package dev.notypie.domain.command.intent

import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.entity.CommandDetailType
import java.time.LocalDateTime
import java.util.UUID

/**
 * Abstract input to the infrastructure resolver.
 *
 * Each variant carries its own [commandDetailType] so that a single command producing
 * heterogeneous intents in one batch (e.g. `RequestMeetingContext` sending a reply to
 * the requester AND notices to participants) can still be routed back to the correct
 * context when the user later interacts with the resulting Slack message.
 *
 * Variants default to their natural detail type, but a producing context may override
 * to change routing (e.g. [ApplyReject] defaults to [CommandDetailType.REQUEST_APPLY_FORM]
 * but is overridden to [CommandDetailType.MEETING_APPROVAL_NOTICE_FORM] when sent as a
 * meeting participant notice).
 */
sealed class CommandIntent {
    abstract val commandDetailType: CommandDetailType

    data class TextResponse(
        val headLine: String,
        val message: String,
        override val commandDetailType: CommandDetailType = CommandDetailType.SIMPLE_TEXT,
    ) : CommandIntent()

    data class EphemeralResponse(
        val message: String,
        val targetUserId: String? = null,
        override val commandDetailType: CommandDetailType = CommandDetailType.SIMPLE_TEXT,
    ) : CommandIntent()

    data class ErrorDetail(
        val errorClassName: String,
        val errorMessage: String,
        val details: String?,
        override val commandDetailType: CommandDetailType = CommandDetailType.ERROR_RESPONSE,
    ) : CommandIntent()

    data class TimeSchedule(
        val headLine: String,
        val timeScheduleInfo: TimeScheduleInfo,
        override val commandDetailType: CommandDetailType = CommandDetailType.SIMPLE_TEXT,
    ) : CommandIntent()

    /**
     * The button value embedded by the Slack template uses [ApprovalContents.commandDetailType]
     * for click-time routing. Deriving the envelope [commandDetailType] from the same field
     * enforces a single source of truth: the two can never drift.
     *
     * Callers that want a specific routing target should set it on [approvalContents]
     * when constructing this intent.
     */
    data class ApplyReject(
        val approvalContents: ApprovalContents,
        val targetUserId: String? = null,
    ) : CommandIntent() {
        override val commandDetailType: CommandDetailType
            get() = approvalContents.commandDetailType
    }

    data class ApprovalForm(
        val headLine: String,
        val selectionFields: List<SelectionContents>,
        val reasonInput: TextInputContents? = null,
        val approvalContents: ApprovalContents? = null,
        override val commandDetailType: CommandDetailType = CommandDetailType.APPROVAL_FORM,
    ) : CommandIntent()

    data class MeetingForm(
        val approvalContents: ApprovalContents? = null,
        override val commandDetailType: CommandDetailType = CommandDetailType.REQUEST_MEETING_FORM,
    ) : CommandIntent()

    data class MeetingListRequest(
        val publisherId: String,
        val startDate: LocalDateTime = LocalDateTime.now(),
        val endDate: LocalDateTime = LocalDateTime.now().plusWeeks(1L),
        override val commandDetailType: CommandDetailType = CommandDetailType.GET_MEETING_LIST,
    ) : CommandIntent()

    /**
     * Participant's attendance decision from a meeting-notice DM. Emitted by
     * [dev.notypie.domain.command.entity.context.form.MeetingApprovalResponseContext] when
     * the participant clicks Accept or Decline; persisted by the application-layer
     * listener that binds to the caller's `@Transactional` boundary via BEFORE_COMMIT.
     */
    data class MeetingAttendanceUpdate(
        val meetingIdempotencyKey: UUID,
        val participantUserId: String,
        val isAttending: Boolean,
        val absentReason: RejectReason,
        override val commandDetailType: CommandDetailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
    ) : CommandIntent()

    /**
     * Host's request to cancel a meeting from the inline Cancel button on `/meetup list`.
     * Authorization (host-only) is enforced atomically by the repository's WHERE clause —
     * the intent itself carries the requester so the bridge can pass it through, and the
     * UI side guards against showing the button to non-hosts as defense in depth.
     */
    data class CancelMeeting(
        val meetingUid: UUID,
        val requesterId: String,
        override val commandDetailType: CommandDetailType = CommandDetailType.CANCEL_MEETING,
    ) : CommandIntent()

    data class Notice(
        val targetUserIds: Collection<String>,
        val message: String,
        override val commandDetailType: CommandDetailType = CommandDetailType.SIMPLE_TEXT,
    ) : CommandIntent()

    /**
     * Request to open the decline-reason modal for a participant who clicked the Deny button
     * on a meeting-notice DM. The `triggerId` must be consumed within Slack's 3-second window
     * via the synchronous dispatch path (outbox is bypassed). On `views.open` failure, the
     * application layer falls back to recording the decline with `RejectReason.OTHER`.
     */
    data class OpenDeclineReasonModal(
        val triggerId: String,
        val meetingIdempotencyKey: UUID,
        val participantUserId: String,
        /**
         * Optional meeting title shown as a header section in the modal. Empty means the
         * section is omitted — the REJECT_BUTTON handler doesn't currently carry the title
         * (it only has the tokenized idempotencyKey from the notice's message.text), so
         * callers may pass "" rather than round-tripping a DB fetch.
         */
        val meetingTitle: String = "",
        /**
         * Channel + message_ts of the originating notice DM. Carried through the modal's
         * `private_metadata` so [DeclineReasonSubmissionContext] can `chat.update` the
         * original Accept/Deny notice once the user submits a reason. Empty strings mean
         * the caller has no notice message to update (e.g. synthesized payloads in tests).
         */
        val noticeChannel: String = "",
        val noticeMessageTs: String = "",
        override val commandDetailType: CommandDetailType = CommandDetailType.DECLINE_REASON_MODAL,
    ) : CommandIntent()

    /**
     * Replaces an existing Slack message in place via `chat.update`. Used after the decline
     * modal submits so the original Accept/Deny notice DM collapses into a decline summary —
     * prevents the user from clicking Accept on a stale notice after they've already declined.
     * Routed through the outbox (not latency-sensitive like `views.open`).
     */
    data class UpdateNoticeMessage(
        val channel: String,
        val messageTs: String,
        val markdownText: String,
        override val commandDetailType: CommandDetailType = CommandDetailType.DECLINE_REASON_MODAL,
    ) : CommandIntent()

    data class ReplaceMessage(
        val markdownText: String,
        val responseUrl: String,
        override val commandDetailType: CommandDetailType = CommandDetailType.REPLACE_TEXT,
    ) : CommandIntent()

    data object Nothing : CommandIntent() {
        override val commandDetailType: CommandDetailType = CommandDetailType.NOTHING
    }
}
