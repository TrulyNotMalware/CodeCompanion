package dev.notypie.domain.meet

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CancelMeetingEvent
import dev.notypie.domain.command.entity.event.CancelMeetingPayload
import dev.notypie.domain.command.entity.event.RescheduleMeetingEvent
import dev.notypie.domain.command.entity.event.RescheduleMeetingPayload
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendanceEvent
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendancePayload
import dev.notypie.domain.meet.entity.Meeting
import dev.notypie.domain.meet.entity.RejectReason
import java.time.LocalDateTime
import java.util.UUID

fun createMeeting(
    title: String = "Standup",
    publisher: String = "U001",
    members: Set<String> = setOf("U002", "U003"),
    reason: String = "Daily sync",
    startAt: LocalDateTime = LocalDateTime.now().plusDays(1L),
    endAt: LocalDateTime = startAt.plusHours(1L),
    isCanceled: Boolean = false,
    meetingUid: UUID = UUID.randomUUID(),
) = Meeting(
    title = title,
    publisher = publisher,
    members = members,
    reason = reason,
    startAt = startAt,
    endAt = endAt,
    isCanceled = isCanceled,
    meetingUid = meetingUid,
)

fun createUpdateMeetingAttendanceEvent(
    meetingIdempotencyKey: UUID = UUID.randomUUID(),
    participantUserId: String = TEST_USER_ID,
    isAttending: Boolean = false,
    absentReason: RejectReason = RejectReason.OTHER,
    idempotencyKey: UUID = UUID.randomUUID(),
) = UpdateMeetingAttendanceEvent(
    idempotencyKey = idempotencyKey,
    payload =
        UpdateMeetingAttendancePayload(
            meetingIdempotencyKey = meetingIdempotencyKey,
            participantUserId = participantUserId,
            isAttending = isAttending,
            absentReason = absentReason,
        ),
    type = CommandDetailType.MEETING_APPROVAL_REQUEST,
)

fun createCancelMeetingEvent(
    meetingUid: UUID = UUID.randomUUID(),
    requesterId: String = TEST_USER_ID,
    idempotencyKey: UUID = UUID.randomUUID(),
    responseBasicInfo: CommandBasicInfo = createCommandBasicInfo(idempotencyKey = idempotencyKey),
) = CancelMeetingEvent(
    idempotencyKey = idempotencyKey,
    payload =
        CancelMeetingPayload(
            meetingUid = meetingUid,
            requesterId = requesterId,
            responseBasicInfo = responseBasicInfo,
        ),
    type = CommandDetailType.CANCEL_MEETING,
)

fun createRescheduleMeetingEvent(
    meetingUid: UUID = UUID.randomUUID(),
    requesterId: String = TEST_USER_ID,
    newStartAt: LocalDateTime = LocalDateTime.now().plusDays(1L),
    idempotencyKey: UUID = UUID.randomUUID(),
    responseBasicInfo: CommandBasicInfo = createCommandBasicInfo(idempotencyKey = idempotencyKey),
) = RescheduleMeetingEvent(
    idempotencyKey = idempotencyKey,
    payload =
        RescheduleMeetingPayload(
            meetingUid = meetingUid,
            requesterId = requesterId,
            newStartAt = newStartAt,
            responseBasicInfo = responseBasicInfo,
        ),
    type = CommandDetailType.MEETING_RESCHEDULE_SUBMIT,
)
