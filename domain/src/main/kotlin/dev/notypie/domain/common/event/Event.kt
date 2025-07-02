package dev.notypie.domain.common.event

import java.util.UUID

interface EventPayload {
    val eventId: UUID
}

interface CommandEvent<out T: EventPayload>{
    val commandId: UUID
    val payload: T
    val isInternal: Boolean
    val timestamp: Long
}

abstract class MeetingPayload(
    override val eventId: UUID = UUID.randomUUID(),
    val meetingId: UUID,
    val userId: String
): EventPayload

data class SelectMeetingListEvent(
    val topic: String = "",
    override val commandId: UUID,
    override val timestamp: Long = System.currentTimeMillis(),
    override val isInternal: Boolean = true,
    override val payload: MeetingPayload,
): CommandEvent<MeetingPayload>
