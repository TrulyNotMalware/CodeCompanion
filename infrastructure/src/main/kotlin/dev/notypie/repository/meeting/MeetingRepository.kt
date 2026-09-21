package dev.notypie.repository.meeting

import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.domain.meet.entity.Meeting
import dev.notypie.domain.meet.entity.RejectReason
import java.time.LocalDateTime
import java.util.UUID

interface MeetingRepository {
    fun createNewMeeting(meeting: Meeting, idempotencyKey: UUID, channel: String): Meeting

    fun getMeeting(meetingId: Long): MeetingDto

    fun getAllMeetingByUserId(userId: String): List<MeetingDto>

    fun getMeetingsByUserIdInRange(userId: String, startAt: LocalDateTime, endAt: LocalDateTime): List<MeetingDto>

    fun getParticipants(meetingId: Long): List<String>

    fun updateParticipantAttendance(
        meetingIdempotencyKey: UUID,
        userId: String,
        isAttending: Boolean,
        absentReason: RejectReason,
        absentReasonDetail: String? = null,
    ): Int

    fun participantExists(meetingIdempotencyKey: UUID, userId: String): Boolean

    fun markMeetingCanceled(meetingUid: UUID, requesterId: String): Boolean

    fun rescheduleMeeting(meetingUid: UUID, requesterId: String, newStartAt: LocalDateTime): Boolean

    fun findMeetingByUid(meetingUid: UUID): MeetingDto?

    fun addParticipants(meetingUid: UUID, requesterId: String, participantUserIds: List<String>): AddParticipantResult
}
