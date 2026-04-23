package dev.notypie.repository.meeting

import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.domain.meet.entity.Meeting
import dev.notypie.exception.meeting.throwIfSchemaNotFound
import dev.notypie.repository.meeting.schema.toDomainEntity
import dev.notypie.repository.meeting.schema.toMeetingDto
import dev.notypie.repository.meeting.schema.toSchema
import jakarta.transaction.Transactional
import java.time.LocalDateTime
import java.util.UUID

open class MeetingRepositoryImpl(
    private val jpaMeetingRepository: JpaMeetingRepository,
) : MeetingRepository {
    @Transactional
    override fun createNewMeeting(meeting: Meeting, idempotencyKey: UUID, channel: String): Meeting =
        jpaMeetingRepository
            .save(
                meeting.toSchema(idempotencyKey = idempotencyKey, channel = channel),
            ).toDomainEntity()

    override fun getMeeting(meetingId: Long): MeetingDto =
        jpaMeetingRepository
            .findMeetingWithParticipants(meetingId)
            ?.toMeetingDto()
            .throwIfSchemaNotFound(fieldName = "id", fieldValue = meetingId)

    override fun getAllMeetingByUserId(userId: String): List<MeetingDto> =
        jpaMeetingRepository
            .findAllMeetingByUserId(userId = userId)
            .map { it.toMeetingDto() }
            .toList()

    override fun getMeetingsByUserIdInRange(
        userId: String,
        startAt: LocalDateTime,
        endAt: LocalDateTime,
    ): List<MeetingDto> =
        jpaMeetingRepository
            .findMeetingsByUserIdAndDateRange(userId = userId, startAt = startAt, endAt = endAt)
            .map { it.toMeetingDto() }
            .toList()

    // FIXME direct select from participant table
    override fun getParticipants(meetingId: Long): List<String> =
        getMeeting(meetingId = meetingId).participants.map { it.userId }

    @Transactional
    override fun updateParticipantAttendance(
        meetingIdempotencyKey: UUID,
        userId: String,
        isAttending: Boolean,
        absentReason: RejectReason,
    ): Int =
        jpaMeetingRepository.updateParticipantAttendance(
            meetingIdempotencyKey = meetingIdempotencyKey,
            userId = userId,
            isAttending = isAttending,
            absentReason = absentReason,
        )

    override fun participantExists(meetingIdempotencyKey: UUID, userId: String): Boolean =
        jpaMeetingRepository.existsParticipant(
            meetingIdempotencyKey = meetingIdempotencyKey,
            userId = userId,
        )
}
