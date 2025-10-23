package dev.notypie.repository.meeting

import dev.notypie.domain.meet.dto.Meeting
import dev.notypie.exception.meeting.throwIfSchemaNotFound
import dev.notypie.repository.meeting.schema.MeetingSchema
import dev.notypie.repository.meeting.schema.toMeetingDto
import jakarta.transaction.Transactional

open class MeetingRepositoryImpl(
    private val jpaMeetingRepository: JpaMeetingRepository,
) : MeetingRepository {
    @Transactional
    override fun createNewMeeting(meetingSchema: MeetingSchema): Meeting =
        jpaMeetingRepository.save(meetingSchema).toMeetingDto()

    override fun getMeeting(meetingId: Long): Meeting =
        jpaMeetingRepository
            .findMeetingWithParticipants(meetingId)
            ?.toMeetingDto()
            .throwIfSchemaNotFound(fieldName = "id", fieldValue = meetingId)

    override fun getAllMeetingByUserId(userId: String): List<Meeting> =
        jpaMeetingRepository
            .findAllMeetingByUserId(userId = userId)
            .map { it.toMeetingDto() }
            .toList()

    // FIXME direct select from participant table
    override fun getParticipants(meetingId: Long): List<String> = getMeeting(meetingId = meetingId).participantIds
}
