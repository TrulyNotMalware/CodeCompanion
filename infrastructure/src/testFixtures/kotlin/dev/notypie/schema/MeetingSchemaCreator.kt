package dev.notypie.schema

import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.repository.meeting.schema.MeetingSchema
import dev.notypie.repository.meeting.schema.ParticipantsSchema
import java.time.LocalDateTime
import java.util.UUID

fun createMeetingSchema(
    id: Long = 0L,
    meetingUid: UUID = UUID.randomUUID(),
    idempotencyKey: UUID = UUID.randomUUID(),
    name: String = "test meeting schema",
    startAt: LocalDateTime = LocalDateTime.now(),
    endAt: LocalDateTime? = null,
    isCanceled: Boolean = false,
    publisherId: String = TEST_USER_ID,
    channel: String = TEST_CHANNEL_ID,
    participants: MutableList<ParticipantsSchema> = mutableListOf(),
) = MeetingSchema(
    id = id,
    meetingUid = meetingUid,
    idempotencyKey = idempotencyKey,
    name = name,
    startAt = startAt,
    endAt = endAt,
    isCanceled = isCanceled,
    publisherId = publisherId,
    channel = channel,
    participants = participants,
)

fun createParticipants(
    id: Long = 0L,
    meeting: MeetingSchema = createMeetingSchema(),
    userId: String = TEST_USER_ID,
    isAttending: Boolean = true,
    absentReason: RejectReason = RejectReason.ATTENDING,
    createdAt: LocalDateTime = LocalDateTime.now(),
    updatedAt: LocalDateTime? = null,
) = ParticipantsSchema(
    id = id,
    meeting = meeting,
    userId = userId,
    isAttending = isAttending,
    absentReason = absentReason,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun createMeetingSchemaWithParticipant(
    publisherId: String = TEST_USER_ID,
    participantUserId: String = TEST_USER_ID,
    name: String = "test meeting schema",
    startAt: LocalDateTime = LocalDateTime.now(),
): MeetingSchema {
    val meeting =
        createMeetingSchema(
            publisherId = publisherId,
            name = name,
            startAt = startAt,
        )
    meeting.participants.add(createParticipants(meeting = meeting, userId = participantUserId))
    return meeting
}

fun createMeetingSchema(member: Int, startIterator: Int = 1): MeetingSchema {
    val meeting =
        createMeetingSchema(
            publisherId = TEST_USER_ID + startIterator,
        )
    val participantsList =
        MutableList(size = member) { iterator ->
            createParticipants(meeting = meeting, userId = TEST_USER_ID + (startIterator + iterator))
        }
    meeting.participants.addAll(participantsList)
    return meeting
}
