package dev.notypie.repository.meeting

import dev.notypie.repository.meeting.schema.MeetingSchema

class MeetingRepositoryImpl(
    private val jpaMeetingRepository: JpaMeetingRepository
): MeetingRepository {
    override fun createNewMeeting(meetingSchema: MeetingSchema): MeetingSchema {
        return jpaMeetingRepository.save(meetingSchema)
    }

    override fun getMeeting(meetingId: Long): MeetingSchema {
        TODO("Not yet implemented")
    }

    override fun getParticipants(meetingId: Long): List<String> {
        TODO("Not yet implemented")
    }

}