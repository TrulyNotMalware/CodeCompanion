package dev.notypie.domain.meet

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.meet.dto.MeetingDto
import dev.notypie.domain.meet.dto.MeetingParticipantDto
import dev.notypie.domain.meet.entity.RejectReason
import java.time.LocalDateTime
import java.util.UUID

fun createMeetingDto(
    meetingId: Long = 0L,
    meetingUid: UUID = UUID.randomUUID(),
    idempotencyKey: UUID = UUID.randomUUID(),
    creator: String = TEST_USER_ID,
    title: String = "Test Meeting",
    reason: String = "Test reason",
    startAt: LocalDateTime = LocalDateTime.now().plusDays(1L),
    endAt: LocalDateTime? = startAt.plusHours(1L),
    participants: List<MeetingParticipantDto> = listOf(),
    isCanceled: Boolean = false,
) = MeetingDto(
    meetingId = meetingId,
    meetingUid = meetingUid,
    idempotencyKey = idempotencyKey,
    creator = creator,
    title = title,
    reason = reason,
    startAt = startAt,
    endAt = endAt,
    participants = participants,
    isCanceled = isCanceled,
)

fun createMeetingParticipantDto(
    userId: String,
    isAttending: Boolean = true,
    absentReason: RejectReason = RejectReason.ATTENDING,
    absentReasonDetail: String? = null,
) = MeetingParticipantDto(
    userId = userId,
    isAttending = isAttending,
    absentReason = absentReason,
    absentReasonDetail = absentReasonDetail,
)
