package dev.notypie.repository.meeting

import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.domain.meet.entity.Meeting
import dev.notypie.exception.meeting.throwIfSchemaNotFound
import dev.notypie.repository.meeting.schema.toDomainEntity
import dev.notypie.repository.meeting.schema.toMeetingDto
import dev.notypie.repository.meeting.schema.toSchema
import jakarta.transaction.Transactional
import java.util.UUID

open class MeetingRepositoryImpl(
    private val jpaMeetingRepository: JpaMeetingRepository,
) : MeetingRepository {
    @Transactional
    override fun createNewMeeting(meeting: Meeting, idempotencyKey: UUID, channel: String): Meeting =
        jpaMeetingRepository
            .save(
                meeting.toSchema(idempotencyKey = idempotencyKey, channel = channel),
            ).toDomainEntity()

    override fun getMeeting(meetingId: Long): MeetingDto =
        jpaMeetingRepository
            .findMeetingWithParticipants(meetingId)
            ?.toMeetingDto()
            .throwIfSchemaNotFound(fieldName = "id", fieldValue = meetingId)

    override fun getAllMeetingByUserId(userId: String): List<MeetingDto> =
        jpaMeetingRepository
            .findAllMeetingByUserId(userId = userId)
            .map { it.toMeetingDto() }
            .toList()

    // FIXME direct select from participant table
    override fun getParticipants(meetingId: Long): List<String> = getMeeting(meetingId = meetingId).participantIds
}
