package dev.notypie.repository.meeting

import dev.notypie.repository.meeting.schema.MeetingSchema

interface MeetingRepository{
    fun createNewMeeting(meetingSchema: MeetingSchema): MeetingSchema
    fun getMeeting(meetingId: Long): MeetingSchema
    fun getAllMeetingByUserId(userId: String): List<MeetingSchema>
    fun getParticipants(meetingId: Long): List<String>
}