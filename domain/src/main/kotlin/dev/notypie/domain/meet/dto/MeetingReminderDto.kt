package dev.notypie.domain.meet.dto

import dev.notypie.domain.meet.entity.enums.MeetingReminderStatus
import java.time.Instant

data class MeetingReminderDto(
    val id: Long,
    val meetingId: Long,
    val offsetMinutes: Int,
    val scheduledAt: Instant,
    val sentAt: Instant?,
    val status: MeetingReminderStatus,
    val failureReason: String?,
)
