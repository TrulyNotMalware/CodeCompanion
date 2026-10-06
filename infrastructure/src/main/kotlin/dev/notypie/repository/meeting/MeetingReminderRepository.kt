package dev.notypie.repository.meeting

import dev.notypie.domain.meet.dto.MeetingReminderDto
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal fun Instant.isSameSecond(other: Instant): Boolean =
    truncatedTo(ChronoUnit.SECONDS) == other.truncatedTo(ChronoUnit.SECONDS)

data class ReadyReminder(
    val reminder: MeetingReminderDto,
    val meetingId: Long,
    val meetingTitle: String,
    val startAt: LocalDateTime,
    val isCanceled: Boolean,
    val attendingUserIds: List<String>,
) {
    fun isArmedFor(zone: ZoneId): Boolean =
        reminder.scheduledAt.isSameSecond(
            other = startAt.atZone(zone).toInstant().minus(Duration.ofMinutes(reminder.offsetMinutes.toLong())),
        )
}

data class ReminderCandidateMeeting(
    val meetingId: Long,
    val startAt: LocalDateTime,
    val attendingUserIds: List<String>,
)

interface MeetingReminderRepository {
    fun findActiveMeetingsInWindow(from: LocalDateTime, to: LocalDateTime): List<ReminderCandidateMeeting>

    fun ensureReminder(
        meetingId: Long,
        offsetMinutes: Int,
        scheduledAt: Instant,
        startAt: LocalDateTime,
        now: Instant,
    ): Boolean

    fun reminderExists(meetingId: Long, offsetMinutes: Int): Boolean

    fun claimReminder(reminderId: Long, claimToken: String, now: Instant): Boolean

    fun markReminderSent(reminderId: Long, claimToken: String, sentAt: Instant): Boolean

    fun markReminderFailed(
        reminderId: Long,
        claimToken: String,
        reason: String,
        now: Instant,
    ): Boolean

    fun resetStuckReminders(olderThan: Instant, now: Instant): Int

    fun findDueBefore(before: Instant, limit: Int): List<ReadyReminder>

    fun deleteByMeetingId(meetingId: Long): Int

    fun discardReminder(reminderId: Long, scheduledAt: Instant): Boolean
}
