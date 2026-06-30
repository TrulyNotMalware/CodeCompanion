package dev.notypie.repository.meeting

import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.domain.meet.entity.Meeting
import dev.notypie.domain.meet.entity.Member
import dev.notypie.domain.meet.entity.RejectReason
import dev.notypie.exception.meeting.throwIfSchemaNotFound
import dev.notypie.repository.meeting.schema.ParticipantsSchema
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
        absentReasonDetail: String?,
    ): Int =
        jpaMeetingRepository.updateParticipantAttendance(
            meetingIdempotencyKey = meetingIdempotencyKey,
            userId = userId,
            isAttending = isAttending,
            absentReason = absentReason,
            absentReasonDetail = absentReasonDetail,
        )

    override fun participantExists(meetingIdempotencyKey: UUID, userId: String): Boolean =
        jpaMeetingRepository.existsParticipant(
            meetingIdempotencyKey = meetingIdempotencyKey,
            userId = userId,
        )

    @Transactional
    override fun markMeetingCanceled(meetingUid: UUID, requesterId: String): Boolean =
        jpaMeetingRepository.markMeetingCanceled(
            meetingUid = meetingUid,
            requesterId = requesterId,
        ) == 1

    @Transactional
    override fun rescheduleMeeting(meetingUid: UUID, requesterId: String, newStartAt: LocalDateTime): Boolean =
        jpaMeetingRepository.rescheduleMeeting(
            meetingUid = meetingUid,
            requesterId = requesterId,
            newStartAt = newStartAt,
        ) == 1

    override fun findMeetingByUid(meetingUid: UUID): MeetingDto? =
        jpaMeetingRepository
            .findMeetingByUidWithParticipants(meetingUid = meetingUid)
            ?.toMeetingDto()

    @Transactional
    override fun addParticipants(
        meetingUid: UUID,
        requesterId: String,
        participantUserIds: List<String>,
    ): AddParticipantResult {
        val schema =
            jpaMeetingRepository.findMeetingByUidWithParticipants(meetingUid = meetingUid)
                ?: return AddParticipantResult(outcome = AddParticipantResult.Outcome.MEETING_NOT_FOUND)
        if (schema.publisherId != requesterId || schema.isCanceled) {
            return AddParticipantResult(outcome = AddParticipantResult.Outcome.NOT_AUTHORIZED)
        }
        // The Meeting aggregate models a future event (its constructor requires startAt in the future),
        // so adding members to an already-started meeting is not a representable domain operation.
        if (!schema.startAt.isAfter(LocalDateTime.now())) {
            return AddParticipantResult(
                outcome = AddParticipantResult.Outcome.MEETING_STARTED,
                meeting = schema.toMeetingDto(),
            )
        }
        val existing = schema.participants.map { it.userId }.toSet() + schema.publisherId
        val newUserIds =
            participantUserIds
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .filter { it !in existing }
        if (newUserIds.isEmpty()) {
            return AddParticipantResult(
                outcome = AddParticipantResult.Outcome.NO_NEW_PARTICIPANTS,
                meeting = schema.toMeetingDto(),
            )
        }
        // Enforce MAX_PARTICIPANTS through the Meeting aggregate before writing any row, so the limit
        // stays owned by the domain entity rather than duplicated here.
        val withinCapacity =
            runCatching {
                val meeting = schema.toDomainEntity()
                newUserIds.forEach { meeting.addParticipant(user = Member(userId = it)) }
            }.isSuccess
        if (!withinCapacity) {
            return AddParticipantResult(
                outcome = AddParticipantResult.Outcome.OVER_CAPACITY,
                meeting = schema.toMeetingDto(),
            )
        }
        newUserIds.forEach { schema.participants.add(ParticipantsSchema(meeting = schema, userId = it)) }
        jpaMeetingRepository.save(schema)
        return AddParticipantResult(
            outcome = AddParticipantResult.Outcome.ADDED,
            addedUserIds = newUserIds,
            meeting = schema.toMeetingDto(),
        )
    }
}
