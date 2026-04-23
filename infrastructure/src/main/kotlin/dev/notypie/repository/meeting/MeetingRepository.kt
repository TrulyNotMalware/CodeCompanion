package dev.notypie.repository.meeting

import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.domain.meet.entity.Meeting
import java.time.LocalDateTime
import java.util.UUID

interface MeetingRepository {
    fun createNewMeeting(meeting: Meeting, idempotencyKey: UUID, channel: String): Meeting

    fun getMeeting(meetingId: Long): MeetingDto

    fun getAllMeetingByUserId(userId: String): List<MeetingDto>

    fun getMeetingsByUserIdInRange(userId: String, startAt: LocalDateTime, endAt: LocalDateTime): List<MeetingDto>

    fun getParticipants(meetingId: Long): List<String>

    /**
     * Updates a single participant's attendance decision on the meeting identified by
     * [meetingIdempotencyKey]. Returns the number of rows actually modified — which is 0
     * both when the row is missing AND when the update is a no-op (same values). Callers
     * that need to distinguish those cases must also call [participantExists].
     */
    fun updateParticipantAttendance(
        meetingIdempotencyKey: UUID,
        userId: String,
        isAttending: Boolean,
        absentReason: RejectReason,
    ): Int

    /** True if a participant row exists for `(meetingIdempotencyKey, userId)`. */
    fun participantExists(meetingIdempotencyKey: UUID, userId: String): Boolean
}
