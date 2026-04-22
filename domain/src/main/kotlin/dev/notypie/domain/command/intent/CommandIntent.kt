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

    data class Notice(
        val targetUserIds: Collection<String>,
        val message: String,
        override val commandDetailType: CommandDetailType = CommandDetailType.SIMPLE_TEXT,
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
