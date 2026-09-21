package dev.notypie.domain.command.intent

import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.meet.entity.RejectReason
import java.time.LocalDateTime
import java.util.UUID

sealed class CommandIntent : CommandEffect {
    data class MeetingListRequest(
        val publisherId: String,
        val startDate: LocalDateTime = LocalDateTime.now(),
        val endDate: LocalDateTime = LocalDateTime.now().plusWeeks(1L),
    ) : CommandIntent()

    // Persisted via a BEFORE_COMMIT listener bound to the caller's `@Transactional` boundary.
    data class MeetingAttendanceUpdate(
        val meetingIdempotencyKey: UUID,
        val participantUserId: String,
        val isAttending: Boolean,
        val absentReason: RejectReason,
        val absentReasonDetail: String? = null,
    ) : CommandIntent()

    // Host-only authorization is enforced atomically by the repository's WHERE clause.
    data class CancelMeeting(
        val meetingUid: UUID,
        val requesterId: String,
    ) : CommandIntent()

    data class RescheduleMeeting(
        val meetingUid: UUID,
        val requesterId: String,
        val newStartAt: LocalDateTime,
    ) : CommandIntent()

    data class AddParticipant(
        val meetingUid: UUID,
        val requesterId: String,
        val participantUserIds: List<String>,
    ) : CommandIntent()

    data object StatusReport : CommandIntent()

    data class GrantRole(
        val targetUserId: String,
        val role: UserRole,
    ) : CommandIntent()

    data class RevokeRole(
        val targetUserId: String,
    ) : CommandIntent()

    data object ListRoles : CommandIntent()

    // Runs in an async listener — must never block the inbound thread or its transaction.
    data class AgentConverse(
        val prompt: String,
        val threadId: String?,
        val requesterName: String,
        val channelName: String,
    ) : CommandIntent()

    data class RecordStandupAnswer(
        val sessionUid: UUID,
        val userId: String,
        val responses: List<String>,
    ) : CommandIntent()

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

    data class CveSubscribe(
        val userId: String,
        val topicKeys: List<String>,
    ) : CommandIntent()

    data class CveUnsubscribe(
        val userId: String,
        val topicKeys: List<String>,
    ) : CommandIntent()

    data class CveListSubscriptions(
        val userId: String,
    ) : CommandIntent()

    data class CveLatest(
        val userId: String,
        val topicKey: String?,
    ) : CommandIntent()

    data object CveListTopics : CommandIntent()

    data class CveSetTopicActive(
        val topicKey: String,
        val active: Boolean,
    ) : CommandIntent()

    data object CveRetryDeadLetters : CommandIntent()

    data class CveRetryDeadLetter(
        val eventId: Long,
    ) : CommandIntent()

    data object Nothing : CommandIntent()
}
