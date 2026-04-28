package dev.notypie.domain.meet

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendanceEvent
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendancePayload
import java.util.UUID

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
    type = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
)
