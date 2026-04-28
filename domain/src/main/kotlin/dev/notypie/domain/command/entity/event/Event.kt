package dev.notypie.domain.command.entity.event

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.domain.command.entity.CommandDetailType
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
