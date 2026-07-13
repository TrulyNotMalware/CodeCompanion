package dev.notypie.domain.command.intent

import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.meet.entity.RejectReason
import java.time.LocalDateTime
import java.util.UUID

/**
 * Abstract input to the infrastructure resolver, which maps each variant to a domain event and
 * assigns the routing detail type used to route later interactions back to the correct context.
 */
sealed class CommandIntent : CommandEffect {
    data class MeetingListRequest(
        val publisherId: String,
        val startDate: LocalDateTime = LocalDateTime.now(),
        val endDate: LocalDateTime = LocalDateTime.now().plusWeeks(1L),
    ) : CommandIntent()

    /**
     * Participant's Accept/Decline from a meeting-notice DM. Persisted via a BEFORE_COMMIT
     * listener bound to the caller's `@Transactional` boundary.
     */
    data class MeetingAttendanceUpdate(
        val meetingIdempotencyKey: UUID,
        val participantUserId: String,
        val isAttending: Boolean,
        val absentReason: RejectReason,
        val absentReasonDetail: String? = null,
    ) : CommandIntent()

    /**
     * Host's meeting cancellation from `/meetup list`. Host-only authorization is enforced
     * atomically by the repository's WHERE clause; the UI hides the button from non-hosts.
     */
    data class CancelMeeting(
        val meetingUid: UUID,
        val requesterId: String,
    ) : CommandIntent()

    /**
     * Host's confirmed reschedule from the modal submission. Host-only authorization is enforced
     * atomically by the repository's WHERE clause.
     */
    data class RescheduleMeeting(
        val meetingUid: UUID,
        val requesterId: String,
        val newStartAt: LocalDateTime,
    ) : CommandIntent()

    /**
     * Host's confirmed participant additions from the modal submission. Host-only authorization,
     * de-duplication, and the `MAX_PARTICIPANTS` invariant are enforced by the application service.
     */
    data class AddParticipant(
        val meetingUid: UUID,
        val requesterId: String,
        val participantUserIds: List<String>,
    ) : CommandIntent()

    /** Ops-tooling request from `@bot status`; routing context lives on the resolver's basicInfo. */
    data object StatusReport : CommandIntent()

    /** Admin-only role grant from `@bot grant @user <role>`; row upsert in `user_command_role`. */
    data class GrantRole(
        val targetUserId: String,
        val role: UserRole,
    ) : CommandIntent()

    /** Admin-only grant removal from `@bot revoke @user`; the target falls back to USER. */
    data class RevokeRole(
        val targetUserId: String,
    ) : CommandIntent()

    /** Admin-only listing of every explicit role grant, from `@bot roles`. */
    data object ListRoles : CommandIntent()

    /**
     * One AI-agent conversation turn from an `@bot ask` (or free-text) mention. The turn itself
     * runs in an async application listener — the LLM call is a slow external network call that
     * must never hold the inbound request thread or its transaction. [threadId] is the
     * transport-level conversation anchor (`thread ?: message` of the mention): the listener keys
     * session continuity on it and posts the reply into the same thread. Null when the transport
     * gave no message identity; the listener then replies un-threaded without session continuity.
     * [requesterName]/[channelName] are display names the listener folds into the per-request
     * agent context (the ids already ride on the resolver's basicInfo).
     */
    data class AgentConverse(
        val prompt: String,
        val threadId: String?,
        val requesterName: String,
        val channelName: String,
    ) : CommandIntent()

    /** Persists standup answers; resubmission replaces the prior `(session_id, user_id)` row. */
    data class RecordStandupAnswer(
        val sessionUid: UUID,
        val userId: String,
        val responses: List<String>,
    ) : CommandIntent()

    /**
     * Persists a brand-new standup routine assembled from the setup modal's state values.
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
    ) : CommandIntent()

    /** Subscribes [userId] to the topics identified by [topicKeys]; unknown keys are skipped downstream. */
    data class CveSubscribe(
        val userId: String,
        val topicKeys: List<String>,
    ) : CommandIntent()

    /** Removes [userId]'s subscriptions to the topics identified by [topicKeys]. */
    data class CveUnsubscribe(
        val userId: String,
        val topicKeys: List<String>,
    ) : CommandIntent()

    /** Lists [userId]'s current topic subscriptions, delivered as a DM. */
    data class CveListSubscriptions(
        val userId: String,
    ) : CommandIntent()

    data object Nothing : CommandIntent()
}
