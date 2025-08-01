package dev.notypie.domain.common.event

import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import java.time.LocalDateTime
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
    val publisherId: String,
): EventPayload

class GetMeetingEventPayload( //FIXME slackEventModifier
    val slackEventModifier: (commandBasicInfo: CommandBasicInfo, commandType: CommandType, commandDetailType: CommandDetailType) -> SendSlackMessageEvent,
    val startDate: LocalDateTime = LocalDateTime.now(),
    val endDate: LocalDateTime = LocalDateTime.now().plusWeeks(1L)
): MeetingPayload(
    eventId = UUID.randomUUID(),
    meetingId = UUID.randomUUID(),
    publisherId = "",
)

data class GetMeetingListEvent(
    override val idempotencyKey: UUID,
    override val timestamp: Long = System.currentTimeMillis(),
    override val isInternal: Boolean = true,
    override val destination: String = "",
    override val payload: GetMeetingEventPayload,
): CommandEvent<MeetingPayload>

data class SendSlackMessageEvent(
    override val idempotencyKey: UUID,
    override val payload: SlackEventPayload,
    override val isInternal: Boolean = true,
    override val destination: String,
    override val timestamp: Long,
    val eventType: MessageType
): CommandEvent<SlackEventPayload>