package dev.notypie.repository.meeting

import dev.notypie.repository.meeting.schema.MeetingSchema
import jakarta.transaction.Transactional

open class MeetingRepositoryImpl(
    private val jpaMeetingRepository: JpaMeetingRepository
): MeetingRepository {

    @Transactional
    override fun createNewMeeting(meetingSchema: MeetingSchema): MeetingSchema =
        jpaMeetingRepository.save(meetingSchema)


    override fun getMeeting(meetingId: Long): MeetingSchema {
        TODO("Not yet implemented")
    }

    override fun getParticipants(meetingId: Long): List<String> {
        TODO("Not yet implemented")
    }

}