package dev.notypie.domain.meet.dto

import dev.notypie.domain.meet.entity.enums.MeetingReminderStatus
import java.time.Instant

/**
 * Read-side projection of a [dev.notypie.domain.meet.entity.MeetingReminder]. Returned by the
 * repository; consumed by the scheduling service that does not need the entity's invariants.
 */
data class MeetingReminderDto(
    val id: Long,
    val meetingId: Long,
    val offsetMinutes: Int,
    val scheduledAt: Instant,
    val sentAt: Instant?,
    val status: MeetingReminderStatus,
    val failureReason: String?,
)
