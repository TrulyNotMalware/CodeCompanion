package dev.notypie.domain.command.intent

import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.meet.entity.RejectReason
import java.time.LocalDateTime
import java.util.UUID

/**
 * Abstract input to the infrastructure resolver.
 *
 * Each variant carries its own [commandDetailType] so that a single command producing
 * heterogeneous intents in one batch can still be routed back to the correct context when
 * the user later interacts with the resulting Slack message.
 *
 * Variants default to their natural detail type, but a producing context may override
 * to change routing.
 */
sealed class CommandIntent : CommandEffect {
    abstract val commandDetailType: CommandDetailType

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
        val absentReasonDetail: String? = null,
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

    /**
     * Host's request to open the reschedule modal from the inline Reschedule button on
     * `/meetup list`. The resolver builds a synchronous
     * [dev.notypie.domain.command.entity.event.OpenViewEvent] so the Slack `trigger_id` is
     * consumed within its 3-second window — mirroring [OpenDeclineReasonModal]. The modal's
     * `private_metadata` carries [meetingUid] + [requesterId] back to the submission handler.
     */
    data class OpenRescheduleMeetingModal(
        val triggerId: String,
        val meetingUid: UUID,
        val requesterId: String,
        // Channel the `/meetup list` message lives in, ferried through the modal's private_metadata so
        // the host's confirmation posts back in-channel (a view_submission carries no channel).
        val channel: String,
        override val commandDetailType: CommandDetailType = CommandDetailType.RESCHEDULE_MEETING,
    ) : CommandIntent()

    /**
     * Host's confirmed reschedule from the modal submission. Authorization (host-only) is
     * enforced atomically by the repository's WHERE clause — the intent carries the requester
     * so the bridge can pass it through. The resolver lifts this to a
     * [dev.notypie.domain.command.entity.event.RescheduleMeetingEvent] consumed by the
     * application-layer service that owns the meeting update + reminder re-arm + re-notification.
     */
    data class RescheduleMeeting(
        val meetingUid: UUID,
        val requesterId: String,
        val newStartAt: LocalDateTime,
        // Channel ferried through the modal's private_metadata so the host's confirmation posts back
        // into the `/meetup list` channel rather than failing on the channel-less submission.
        val channel: String,
        override val commandDetailType: CommandDetailType = CommandDetailType.RESCHEDULE_MEETING_SUBMIT,
    ) : CommandIntent()

    /**
     * Host's request to open the add-participant modal from the inline "Add participant" button on
     * `/meetup list`. The resolver builds a synchronous
     * [dev.notypie.domain.command.entity.event.OpenViewEvent] so the Slack `trigger_id` is consumed
     * within its 3-second window — mirroring [OpenRescheduleMeetingModal]. The modal's
     * `private_metadata` carries [meetingUid] + [requesterId] back to the submission handler.
     */
    data class OpenAddParticipantModal(
        val triggerId: String,
        val meetingUid: UUID,
        val requesterId: String,
        // Channel the `/meetup list` message lives in. A view_submission carries no channel, so we
        // ferry it through the modal's private_metadata to post the host's confirmation back in-channel.
        val channel: String,
        override val commandDetailType: CommandDetailType = CommandDetailType.ADD_PARTICIPANT,
    ) : CommandIntent()

    /**
     * Host's confirmed participant additions from the modal submission. Authorization (host-only),
     * de-duplication against existing members, and the `MAX_PARTICIPANTS` invariant are all enforced
     * by the application-layer service via the Meeting entity's `addParticipant`. The resolver lifts
     * this to a [dev.notypie.domain.command.entity.event.AddParticipantEvent].
     */
    data class AddParticipant(
        val meetingUid: UUID,
        val requesterId: String,
        val participantUserIds: List<String>,
        // Channel ferried through the modal's private_metadata so the host's confirmation ephemeral
        // posts back into the `/meetup list` channel rather than failing on the channel-less submission.
        val channel: String,
        override val commandDetailType: CommandDetailType = CommandDetailType.ADD_PARTICIPANT_SUBMIT,
    ) : CommandIntent()

    /**
     * Ops-tooling request emitted when a user invokes `@bot status`. The resolver lifts this
     * to a [dev.notypie.domain.command.entity.event.StatusReportRequestEvent] so the
     * application listener (which has the outbox repository) can render fresh metrics. Carries
     * no fields because all routing context lives on the resolver's [basicInfo] argument.
     */
    data object StatusReport : CommandIntent() {
        override val commandDetailType: CommandDetailType = CommandDetailType.STATUS_REPORT
    }

    /**
     * Opens the answer-entry modal for a scheduled standup prompt. The resolver loads the
     * routine/session read models and builds a synchronous [dev.notypie.domain.command.entity.event.OpenViewEvent]
     * so the Slack `trigger_id` is consumed before it expires.
     */
    data class OpenStandupModal(
        val triggerId: String,
        val sessionUid: UUID,
        val routineUid: UUID,
        val requesterId: String,
        val noticeChannel: String,
        val noticeMessageTs: String,
        override val commandDetailType: CommandDetailType = CommandDetailType.STANDUP_FILL,
    ) : CommandIntent()

    /**
     * Persists the responses from the standup answer modal. Resubmission replaces the prior
     * row for `(session_id, user_id)` in the repository.
     */
    data class RecordStandupAnswer(
        val sessionUid: UUID,
        val userId: String,
        val responses: List<String>,
        override val commandDetailType: CommandDetailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
    ) : CommandIntent()

    /**
     * Opens the standup-setup modal from `/standup setup`. The resolver builds a synchronous
     * [dev.notypie.domain.command.entity.event.OpenViewEvent] so the Slack `trigger_id` is
     * consumed within its 3-second window — mirroring [OpenStandupModal] and
     * [OpenDeclineReasonModal]. [commandChannel] is carried into the modal's `private_metadata`
     * so the submission handler knows where to persist + post the confirmation.
     */
    data class OpenStandupSetupModal(
        val triggerId: String,
        val creatorId: String,
        val commandChannel: String,
        override val commandDetailType: CommandDetailType = CommandDetailType.STANDUP_SETUP_FORM,
    ) : CommandIntent()

    /**
     * Persists a brand-new standup [dev.notypie.domain.standup.entity.Routine] assembled from
     * the setup modal's state values. The resolver lifts this to a
     * [dev.notypie.domain.command.entity.event.CreateStandupRoutineEvent] consumed by the
     * application-layer service that owns the repository write + confirmation post.
     *
     * v1 simplification: every member's `userTimezone` equals [timezone]; per-member zones
     * are not collected by the modal.
     */
    data class CreateStandupRoutine(
        val name: String,
        val creatorId: String,
        val commandChannel: String,
        val summaryChannel: String,
        val questions: List<String>,
        val memberIds: List<String>,
        val weekdays: Set<java.time.DayOfWeek>,
        val triggerLocalTime: java.time.LocalTime,
        val cutoffMinutes: Long,
        val timezone: java.time.ZoneId,
        override val commandDetailType: CommandDetailType = CommandDetailType.STANDUP_SETUP_SUBMIT,
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
