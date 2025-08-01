package dev.notypie.domain.common.event

import java.util.UUID

interface EventPayload {
    val eventId: UUID
}

interface CommandEvent<out T: EventPayload>{
    val idempotencyKey: UUID
    val payload: T
    val destination: String
    val isInternal: Boolean
    val timestamp: Long
}

abstract class MeetingPayload(
    override val eventId: UUID = UUID.randomUUID(),
    val meetingId: UUID,
    val publisherId: String
): EventPayload

class GetMeetingEventPayload(
    //조회 기간
): MeetingPayload(
    eventId = UUID.randomUUID(),
    meetingId = UUID.randomUUID(),
    publisherId = ""
)

data class SelectMeetingListEvent(
    override val idempotencyKey: UUID,
    override val timestamp: Long = System.currentTimeMillis(),
    override val isInternal: Boolean = true,
    override val destination: String = "",
    override val payload: MeetingPayload,
): CommandEvent<MeetingPayload>

data class SendSlackMessageEvent(
    override val idempotencyKey: UUID,
    override val payload: SlackEventPayload,
    override val isInternal: Boolean = true,
    override val destination: String,
    override val timestamp: Long,
    val eventType: MessageType
): CommandEvent<SlackEventPayload>