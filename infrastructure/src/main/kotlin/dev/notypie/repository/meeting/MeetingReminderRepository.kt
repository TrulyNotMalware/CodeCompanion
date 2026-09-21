package dev.notypie.repository.meeting

import dev.notypie.domain.meet.dto.MeetingReminderDto
import java.time.Instant
import java.time.LocalDateTime

data class ReadyReminder(
    val reminder: MeetingReminderDto,
    val meetingId: Long,
    val meetingTitle: String,
    val startAt: LocalDateTime,
    val isCanceled: Boolean,
    val attendingUserIds: List<String>,
)

data class ReminderCandidateMeeting(
    val meetingId: Long,
    val startAt: LocalDateTime,
    val attendingUserIds: List<String>,
)

interface MeetingReminderRepository {
    fun findActiveMeetingsInWindow(from: LocalDateTime, to: LocalDateTime): List<ReminderCandidateMeeting>

    fun ensureReminder(meetingId: Long, offsetMinutes: Int, scheduledAt: Instant): Boolean

    fun reminderExists(meetingId: Long, offsetMinutes: Int): Boolean

    fun claimReminder(reminderId: Long, claimToken: String): Boolean

    fun markReminderSent(reminderId: Long, claimToken: String, sentAt: Instant): Boolean

    fun markReminderFailed(reminderId: Long, claimToken: String, reason: String): Boolean

    fun resetStuckReminders(olderThan: Instant): Int

    fun findDueBefore(before: Instant, limit: Int): List<ReadyReminder>

    fun deleteByMeetingId(meetingId: Long): Int
}
