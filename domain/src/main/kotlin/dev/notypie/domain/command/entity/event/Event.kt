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
