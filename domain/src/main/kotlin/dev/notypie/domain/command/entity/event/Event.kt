package dev.notypie.domain.command.entity.event

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.domain.command.entity.CommandDetailType
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
    /**
     * Basic info of the original command. Required by the async handler to
     * render the meeting list back to Slack using the same channel/app context.
     */
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
    /**
     * Basic info of the originating interaction. Reused by the application-layer listener
     * to send the success/no-op ephemeral back to the requester through the same channel
     * the click came from, without round-tripping through routing extras.
     */
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
    /**
     * Basic info of the originating interaction. Reused by the application-layer listener
     * to send the success/no-op ephemeral back to the requester through the same channel
     * the submission came from, mirroring [CancelMeetingPayload.responseBasicInfo].
     */
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
    /**
     * Basic info of the originating interaction. Reused by the application-layer listener to send the
     * host's confirmation ephemeral back through the same channel the submission came from, mirroring
     * [RescheduleMeetingPayload.responseBasicInfo].
     */
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
    /**
     * Basic info of the `@bot status` mention. The application listener uses it to post the
     * formatted status back to the same channel the request came from.
     */
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
 * Carries the parsed standup-setup modal submission to the application-layer service that
 * builds and persists the [dev.notypie.domain.standup.entity.Routine]. [responseBasicInfo]
 * lets the listener post the confirmation (or a friendly validation error) back to the
 * channel the `/standup setup` command was invoked from.
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
 * Synchronous-dispatch command event carrying a `views.open` payload. Must be consumed on
 * the request thread because [OpenViewPayloadContents.triggerId] expires in 3 seconds.
 * `isInternal = true` so the event is routed through the in-process Spring event bus
 * (never staged in the outbox) and picked up by a dedicated non-`@Async` listener.
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
 * Published by the dispatcher when `views.open` fails (trigger_id expired, Slack API
 * error, network failure, etc.). The application-layer listener is responsible for
 * recording the decline with [dev.notypie.domain.command.dto.interactions.RejectReason.OTHER]
 * and sending an ephemeral notice so the user knows the decline was still accepted.
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
 * Published by the dispatcher when `views.open` for the standup answer modal fails.
 * Unlike the decline-reason flow, no provisional persistence has happened yet — the
 * answers exist only in the unopened modal. The application listener sends an ephemeral
 * notice so the user can retry from the original DM rather than wonder why nothing
 * happened.
 */
data class StandupModalOpenFailedEvent(
    val userId: String,
    val apiAppId: String,
    val channel: String,
    val idempotencyKey: UUID,
    val reason: String,
)
