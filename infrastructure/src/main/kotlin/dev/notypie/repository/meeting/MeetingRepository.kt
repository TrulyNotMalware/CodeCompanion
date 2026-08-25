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

    /**
     * Updates a participant's attendance decision. Returns rows modified — 0 both when the row is
     * missing AND when the update is a no-op, so callers needing to tell them apart call
     * [participantExists].
     */
    fun updateParticipantAttendance(
        meetingIdempotencyKey: UUID,
        userId: String,
        isAttending: Boolean,
        absentReason: RejectReason,
        absentReasonDetail: String? = null,
    ): Int

    /** True if a participant row exists for `(meetingIdempotencyKey, userId)`. */
    fun participantExists(meetingIdempotencyKey: UUID, userId: String): Boolean

    /**
     * Cancels [meetingUid] only when [requesterId] is the host and it is not already canceled.
     * True iff exactly one row changed; the atomic host-only WHERE clause collapses missing,
     * non-host, and already-canceled into a single no-op branch.
     */
    fun markMeetingCanceled(meetingUid: UUID, requesterId: String): Boolean

    /**
     * Moves [meetingUid] to [newStartAt] only when [requesterId] is the host and it is not canceled.
     * Same atomic host-only WHERE-clause guard as [markMeetingCanceled].
     */
    fun rescheduleMeeting(meetingUid: UUID, requesterId: String, newStartAt: LocalDateTime): Boolean

    /** Loads [meetingUid] with its participants, or null; used to re-notify and re-arm reminders. */
    fun findMeetingByUid(meetingUid: UUID): MeetingDto?

    /**
     * Adds [participantUserIds] to [meetingUid] only when [requesterId] is the host and it is neither
     * canceled nor started. Existing members and the host are ignored; the `MAX_PARTICIPANTS`
     * invariant is enforced via the Meeting aggregate. Rejection reasons map to distinct
     * [AddParticipantResult.Outcome] values.
     */
    fun addParticipants(meetingUid: UUID, requesterId: String, participantUserIds: List<String>): AddParticipantResult
}
