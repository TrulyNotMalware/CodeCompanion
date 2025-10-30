package dev.notypie.repository.meeting

import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.repository.meeting.schema.MeetingSchema

interface MeetingRepository {
    fun createNewMeeting(meetingSchema: MeetingSchema): MeetingDto

    fun getMeeting(meetingId: Long): MeetingDto

    fun getAllMeetingByUserId(userId: String): List<MeetingDto>

    fun getParticipants(meetingId: Long): List<String>
}
