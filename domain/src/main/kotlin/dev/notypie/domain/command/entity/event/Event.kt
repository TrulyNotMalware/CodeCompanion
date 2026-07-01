package dev.notypie.domain.command.entity.event

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.meet.entity.RejectReason
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

interface EventPayload {
    val eventId: UUID
}

interface CommandEvent<out T : EventPayload> {
    val idempotencyKey: UUID
    val name: String
    val type: CommandDetailType
    val payload: T
    val destination: String
    val isInternal: Boolean
    val timestamp: Long
}

abstract class MeetingPayload(
    override val eventId: UUID = UUID.randomUUID(),
    val meetingId: UUID,
    val publisherId: String,
) : EventPayload

class GetMeetingEventPayload(
    val startDate: LocalDateTime = LocalDateTime.now(),
    val endDate: LocalDateTime = LocalDateTime.now().plusWeeks(1L),
    publisherId: String,
    /** Original command context; lets the async handler reply on the same channel/app. */
    val responseBasicInfo: CommandBasicInfo,
) : MeetingPayload(
        eventId = UUID.randomUUID(),
        meetingId = UUID.randomUUID(),
        publisherId = publisherId,
    )

data class GetMeetingListEvent(
    override val idempotencyKey: UUID,
    override val name: String = GetMeetingListEvent::class.java.simpleName,
    override val timestamp: Long = System.currentTimeMillis(),
    override val isInternal: Boolean = true,
    override val destination: String = "",
    override val payload: GetMeetingEventPayload,
    override val type: CommandDetailType,
) : CommandEvent<MeetingPayload>

data class SendSlackMessageEvent(
    override val idempotencyKey: UUID,
    override val name: String = SendSlackMessageEvent::class.java.simpleName,
    override val payload: SlackEventPayload,
    override val isInternal: Boolean = true,
    override val destination: String,
    override val timestamp: Long,
    override val type: CommandDetailType,
) : CommandEvent<SlackEventPayload>

class UpdateMeetingAttendancePayload(
    override val eventId: UUID = UUID.randomUUID(),
    val meetingIdempotencyKey: UUID,
    val participantUserId: String,
    val isAttending: Boolean,
    val absentReason: RejectReason,
    val absentReasonDetail: String? = null,
) : EventPayload

data class UpdateMeetingAttendanceEvent(
    override val idempotencyKey: UUID,
    override val name: String = UpdateMeetingAttendanceEvent::class.java.simpleName,
    override val timestamp: Long = System.currentTimeMillis(),
    override val isInternal: Boolean = true,
    override val destination: String = "",
    override val payload: UpdateMeetingAttendancePayload,
    override val type: CommandDetailType,
) : CommandEvent<UpdateMeetingAttendancePayload>

class CancelMeetingPayload(
    override val eventId: UUID = UUID.randomUUID(),
    val meetingUid: UUID,
    val requesterId: String,
    /** Originating interaction context; lets the listener reply on the same channel. */
    val responseBasicInfo: CommandBasicInfo,
) : EventPayload

data class CancelMeetingEvent(
    override val idempotencyKey: UUID,
    override val name: String = CancelMeetingEvent::class.java.simpleName,
    override val timestamp: Long = System.currentTimeMillis(),
    override val isInternal: Boolean = true,
    override val destination: String = "",
    override val payload: CancelMeetingPayload,
    override val type: CommandDetailType,
) : CommandEvent<CancelMeetingPayload>

class RescheduleMeetingPayload(
    override val eventId: UUID = UUID.randomUUID(),
    val meetingUid: UUID,
    val requesterId: String,
    val newStartAt: LocalDateTime,
    /** Originating interaction context; lets the listener reply on the same channel. */
    val responseBasicInfo: CommandBasicInfo,
) : EventPayload

data class RescheduleMeetingEvent(
    override val idempotencyKey: UUID,
    override val name: String = RescheduleMeetingEvent::class.java.simpleName,
    override val timestamp: Long = System.currentTimeMillis(),
    override val isInternal: Boolean = true,
    override val destination: String = "",
    override val payload: RescheduleMeetingPayload,
    override val type: CommandDetailType,
) : CommandEvent<RescheduleMeetingPayload>

class AddParticipantPayload(
    override val eventId: UUID = UUID.randomUUID(),
    val meetingUid: UUID,
    val requesterId: String,
    val participantUserIds: List<String>,
    /** Originating interaction context; lets the listener reply on the same channel. */
    val responseBasicInfo: CommandBasicInfo,
) : EventPayload

data class AddParticipantEvent(
    override val idempotencyKey: UUID,
    override val name: String = AddParticipantEvent::class.java.simpleName,
    override val timestamp: Long = System.currentTimeMillis(),
    override val isInternal: Boolean = true,
    override val destination: String = "",
    override val payload: AddParticipantPayload,
    override val type: CommandDetailType,
) : CommandEvent<AddParticipantPayload>

class StatusReportPayload(
    override val eventId: UUID = UUID.randomUUID(),
    /** `@bot status` mention context; lets the listener post the report on the same channel. */
    val responseBasicInfo: CommandBasicInfo,
) : EventPayload

data class StatusReportRequestEvent(
    override val idempotencyKey: UUID,
    override val name: String = StatusReportRequestEvent::class.java.simpleName,
    override val timestamp: Long = System.currentTimeMillis(),
    override val isInternal: Boolean = true,
    override val destination: String = "",
    override val payload: StatusReportPayload,
    override val type: CommandDetailType,
) : CommandEvent<StatusReportPayload>

class RecordStandupAnswerPayload(
    override val eventId: UUID = UUID.randomUUID(),
    val sessionUid: UUID,
    val userId: String,
    val responses: List<String>,
) : EventPayload

data class RecordStandupAnswerEvent(
    override val idempotencyKey: UUID,
    override val name: String = RecordStandupAnswerEvent::class.java.simpleName,
    override val timestamp: Long = System.currentTimeMillis(),
    override val isInternal: Boolean = true,
    override val destination: String = "",
    override val payload: RecordStandupAnswerPayload,
    override val type: CommandDetailType,
) : CommandEvent<RecordStandupAnswerPayload>

data class StandupCutoffEvent(
    val sessionId: Long,
    val sessionUid: UUID,
    val routineUid: UUID,
    val sessionDate: LocalDate,
)

/**
 * Parsed standup-setup modal submission; [responseBasicInfo] lets the listener post the
 * confirmation (or validation error) back to the invoking channel.
 */
class CreateStandupRoutinePayload(
    override val eventId: UUID = UUID.randomUUID(),
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
    val responseBasicInfo: CommandBasicInfo,
) : EventPayload

data class CreateStandupRoutineEvent(
    override val idempotencyKey: UUID,
    override val name: String = CreateStandupRoutineEvent::class.java.simpleName,
    override val timestamp: Long = System.currentTimeMillis(),
    override val isInternal: Boolean = true,
    override val destination: String = "",
    override val payload: CreateStandupRoutinePayload,
    override val type: CommandDetailType,
) : CommandEvent<CreateStandupRoutinePayload>

/**
 * Synchronous `views.open` command event. Must be consumed on the request thread because
 * [OpenViewPayloadContents.triggerId] expires in 3 seconds; `isInternal = true` keeps it on
 * the in-process event bus (never the outbox), handled by a dedicated non-`@Async` listener.
 */
data class OpenViewEvent(
    override val idempotencyKey: UUID,
    override val name: String = OpenViewEvent::class.java.simpleName,
    override val timestamp: Long = System.currentTimeMillis(),
    override val isInternal: Boolean = true,
    override val destination: String = "",
    override val payload: OpenViewPayloadContents,
    override val type: CommandDetailType,
) : CommandEvent<OpenViewPayloadContents>

/**
 * Published when `views.open` fails (expired trigger_id, Slack/network error). The listener
 * records the decline with [dev.notypie.domain.meet.entity.RejectReason.OTHER] and sends an
 * ephemeral notice so the user knows the decline was still accepted.
 */
data class DeclineModalOpenFailedEvent(
    val meetingIdempotencyKey: UUID,
    val participantUserId: String,
    val apiAppId: String,
    val channel: String,
    val idempotencyKey: UUID,
    val reason: String,
)

/**
 * Published when `views.open` for the standup answer modal fails. Unlike the decline-reason
 * flow nothing has been persisted yet — the answers exist only in the unopened modal — so the
 * listener sends an ephemeral notice prompting the user to retry from the original DM.
 */
data class StandupModalOpenFailedEvent(
    val userId: String,
    val apiAppId: String,
    val channel: String,
    val idempotencyKey: UUID,
    val reason: String,
)
