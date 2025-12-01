package dev.notypie.repository.meeting

import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.domain.meet.entity.Meeting
import java.util.UUID

interface MeetingRepository {
    fun createNewMeeting(meeting: Meeting, idempotencyKey: UUID, channel: String): Meeting

    fun getMeeting(meetingId: Long): MeetingDto

    fun getAllMeetingByUserId(userId: String): List<MeetingDto>

    fun getParticipants(meetingId: Long): List<String>
}
