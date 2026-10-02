package dev.notypie.repository.meeting

import dev.notypie.domain.meet.dto.MeetingReminderDto
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// scheduled_at is a second-precision DATETIME (V5): compare reminder instants at the precision a row can hold.
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
    // False for a row armed from a start time a reschedule has since replaced (review M6): the materialize read the
    // old start before the reschedule deleted the rows, and its insert landed after. `zone` must be the one the
    // materialize used to turn startAt into scheduledAt.
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

    // `startAt` is the meeting start `scheduledAt` was computed from; an existing PENDING row is realigned only while
    // the meeting still starts then.
    fun ensureReminder(
        meetingId: Long,
        offsetMinutes: Int,
        scheduledAt: Instant,
        startAt: LocalDateTime,
    ): Boolean

    fun reminderExists(meetingId: Long, offsetMinutes: Int): Boolean

    fun claimReminder(reminderId: Long, claimToken: String): Boolean

    fun markReminderSent(reminderId: Long, claimToken: String, sentAt: Instant): Boolean

    fun markReminderFailed(reminderId: Long, claimToken: String, reason: String): Boolean

    fun resetStuckReminders(olderThan: Instant): Int

    fun findDueBefore(before: Instant, limit: Int): List<ReadyReminder>

    fun deleteByMeetingId(meetingId: Long): Int

    // Deletes the row only while it is still PENDING at the `scheduledAt` the caller read; false when it was claimed,
    // sent, removed or realigned meanwhile.
    fun discardReminder(reminderId: Long, scheduledAt: Instant): Boolean
}
