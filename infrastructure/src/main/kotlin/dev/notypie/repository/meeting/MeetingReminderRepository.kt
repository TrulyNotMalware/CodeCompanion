package dev.notypie.repository.meeting

import dev.notypie.domain.meet.dto.MeetingReminderDto
import java.time.Instant
import java.time.LocalDateTime

/**
 * Read view returned by [MeetingReminderRepository.findDueBefore]. Carries enough meeting
 * context — the title, start time, and the *attending* participant ids — that the scheduler can
 * build every reminder DM without an extra round-trip per reminder.
 */
data class ReadyReminder(
    val reminder: MeetingReminderDto,
    val meetingId: Long,
    val meetingTitle: String,
    val startAt: LocalDateTime,
    val isCanceled: Boolean,
    val attendingUserIds: List<String>,
)

/**
 * Candidate meeting projection used by the materialization phase. Carries the attending
 * participant ids so the scheduler can decide a reminder is worth creating without re-loading
 * the meeting.
 */
data class ReminderCandidateMeeting(
    val meetingId: Long,
    val startAt: LocalDateTime,
    val attendingUserIds: List<String>,
)

/**
 * Domain-facing repository for the meeting-reminder feature. Mirrors the standup dispatch
 * repository: a materialization side that ensures idempotent reminder rows exist, and a
 * dispatch side built around an atomic claim/mark CAS keyed on a per-claim token.
 */
interface MeetingReminderRepository {
    /** Non-canceled meetings whose `startAt` falls in `[from, to)`, with attending participants. */
    fun findActiveMeetingsInWindow(from: LocalDateTime, to: LocalDateTime): List<ReminderCandidateMeeting>

    /**
     * Ensures a `meeting_reminder` row exists for `(meetingId, offsetMinutes)` with the given
     * fire time. Idempotent via the unique constraint — a concurrent racing tick simply finds
     * the existing row. Returns true iff a new row was persisted.
     */
    fun ensureReminder(meetingId: Long, offsetMinutes: Int, scheduledAt: Instant): Boolean

    /** True if a reminder row already exists for `(meetingId, offsetMinutes)`. */
    fun reminderExists(meetingId: Long, offsetMinutes: Int): Boolean

    /**
     * Atomically transitions PENDING→SENDING and stamps [claimToken] on the row. Callers must
     * generate a fresh token per claim attempt and pass the same token to [markReminderSent] or
     * [markReminderFailed] so the audit transition is keyed to *this* claim.
     */
    fun claimReminder(reminderId: Long, claimToken: String): Boolean

    /**
     * Atomically transitions SENDING→SENT, but only when [claimToken] matches the row's current
     * token. Returns true iff exactly one row changed; false means recovery or another tick has
     * already moved the row out of *our* claim and the outbox-side write should be rolled back.
     */
    fun markReminderSent(reminderId: Long, claimToken: String, sentAt: Instant): Boolean

    /**
     * Atomically transitions SENDING→FAILED, but only when [claimToken] matches. Returns true
     * iff exactly one row changed; false means our claim was already invalidated.
     */
    fun markReminderFailed(reminderId: Long, claimToken: String, reason: String): Boolean

    /** Resets SENDING reminders older than threshold back to PENDING (crash recovery). */
    fun resetStuckReminders(olderThan: Instant): Int

    /**
     * Finds PENDING reminders ready to send (`scheduledAt <= before`) on non-canceled meetings,
     * eagerly joined with their meeting + attending participants so the scheduler can build the
     * reminder DMs without re-loading the meeting.
     */
    fun findDueBefore(before: Instant, limit: Int): List<ReadyReminder>

    /**
     * Deletes all reminder rows for [meetingId]. Used on reschedule so the scheduler's next
     * materialization tick recreates the rows at the new start's offsets — delete-and-recreate
     * is simplest and correct because materialization already skips long-past offsets. Returns
     * the number of rows removed.
     */
    fun deleteByMeetingId(meetingId: Long): Int
}
