package dev.notypie.domain.meet

import dev.notypie.domain.meet.dto.MeetingReminderDto
import dev.notypie.domain.meet.entity.enums.MeetingReminderStatus
import java.time.Instant

fun createMeetingReminderDto(
    id: Long = 1L,
    meetingId: Long = 1L,
    offsetMinutes: Int = 15,
    scheduledAt: Instant = Instant.parse("2026-05-01T01:00:00Z"),
    sentAt: Instant? = null,
    status: MeetingReminderStatus = MeetingReminderStatus.PENDING,
    failureReason: String? = null,
) = MeetingReminderDto(
    id = id,
    meetingId = meetingId,
    offsetMinutes = offsetMinutes,
    scheduledAt = scheduledAt,
    sentAt = sentAt,
    status = status,
    failureReason = failureReason,
)
