package dev.notypie.repository.meeting

import dev.notypie.domain.meet.dto.Meeting
import dev.notypie.repository.meeting.schema.MeetingSchema

interface MeetingRepository {
    fun createNewMeeting(meetingSchema: MeetingSchema): Meeting

    fun getMeeting(meetingId: Long): Meeting

    fun getAllMeetingByUserId(userId: String): List<Meeting>

    fun getParticipants(meetingId: Long): List<String>
}
