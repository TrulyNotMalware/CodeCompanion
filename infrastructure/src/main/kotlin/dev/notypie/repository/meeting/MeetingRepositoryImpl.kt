package dev.notypie.repository.meeting

import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.domain.meet.entity.Meeting
import dev.notypie.domain.meet.entity.RejectReason
import dev.notypie.exception.meeting.throwIfSchemaNotFound
import dev.notypie.repository.meeting.schema.MeetingSchema
import dev.notypie.repository.meeting.schema.toDomainEntity
import dev.notypie.repository.meeting.schema.toMeetingDto
import dev.notypie.repository.meeting.schema.toSchema
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

open class MeetingRepositoryImpl(
    private val jpaMeetingRepository: JpaMeetingRepository,
    private val clock: Clock,
) : MeetingRepository {
    @Transactional
    override fun createNewMeeting(meeting: Meeting, idempotencyKey: UUID, channel: String): Meeting =
        jpaMeetingRepository
            .save(
                meeting.toSchema(idempotencyKey = idempotencyKey, channel = channel),
            ).toDomainEntity()

    @Transactional(readOnly = true)
    override fun getMeeting(meetingId: Long): MeetingDto =
        jpaMeetingRepository
            .findMeetingWithParticipants(meetingId)
            ?.toMeetingDto()
            .throwIfSchemaNotFound(fieldName = "id", fieldValue = meetingId)

    @Transactional(readOnly = true)
    override fun findMeetingId(idempotencyKey: UUID): Long? =
        jpaMeetingRepository.findIdByIdempotencyKey(idempotencyKey = idempotencyKey)

    @Transactional(readOnly = true)
    override fun getAllMeetingByUserId(userId: String): List<MeetingDto> =
        jpaMeetingRepository
            .findAllMeetingByUserId(userId = userId)
            .map { it.toMeetingDto() }
            .toList()

    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
    override fun participantExists(meetingIdempotencyKey: UUID, userId: String): Boolean =
        jpaMeetingRepository.existsParticipant(
            meetingIdempotencyKey = meetingIdempotencyKey,
            userId = userId,
        )

    @Transactional
    override fun markMeetingCanceled(meetingUid: UUID, requesterId: String): MeetingDto? {
        val schema = findActiveMeetingOwnedBy(meetingUid = meetingUid, requesterId = requesterId) ?: return null
        schema.cancel()
        jpaMeetingRepository.saveAndFlush(schema)
        return schema.toMeetingDto()
    }

    @Transactional
    override fun rescheduleMeeting(
        meetingUid: UUID,
        requesterId: String,
        newStartAt: LocalDateTime,
    ): RescheduleResult {
        val schema =
            findActiveMeetingOwnedBy(meetingUid = meetingUid, requesterId = requesterId)
                ?: return RescheduleResult.NotAuthorized
        if (schema.startAt == newStartAt) return RescheduleResult.AlreadyAtRequestedTime
        schema.reschedule(newStartAt = newStartAt)
        jpaMeetingRepository.saveAndFlush(schema)
        return RescheduleResult.Rescheduled(meeting = schema.toMeetingDto())
    }

    private fun findActiveMeetingOwnedBy(meetingUid: UUID, requesterId: String): MeetingSchema? =
        jpaMeetingRepository
            .findMeetingByUidWithParticipants(meetingUid = meetingUid)
            ?.takeIf { it.publisherId == requesterId && !it.isCanceled }

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
        if (!schema.startAt.isAfter(LocalDateTime.now(clock))) {
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
        if (schema.participants.size + newUserIds.size > Meeting.MAX_PARTICIPANTS) {
            return AddParticipantResult(
                outcome = AddParticipantResult.Outcome.OVER_CAPACITY,
                meeting = schema.toMeetingDto(),
            )
        }
        schema.addParticipants(userIds = newUserIds)
        jpaMeetingRepository.saveAndFlush(schema)
        return AddParticipantResult(
            outcome = AddParticipantResult.Outcome.ADDED,
            addedUserIds = newUserIds,
            meeting = schema.toMeetingDto(),
        )
    }
}
