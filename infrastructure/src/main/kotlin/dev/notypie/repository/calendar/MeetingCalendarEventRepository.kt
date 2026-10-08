package dev.notypie.repository.calendar

import dev.notypie.repository.calendar.schema.CalendarSyncStatus
import dev.notypie.repository.calendar.schema.MeetingCalendarEvent
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class CalendarMeetingView(
    val meetingId: Long,
    val meetingUid: UUID,
    val title: String,
    val reason: String,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime,
    val isCanceled: Boolean,
    val hostId: String,
    val attendingUserIds: Set<String>,
)

interface MeetingCalendarEventRepository {
    fun enqueue(meetingId: Long, slackUserId: String, now: Instant)

    fun touchMeeting(meetingId: Long, now: Instant): Int

    fun touchMeetingByUid(meetingUid: UUID, now: Instant): Int

    fun touchOne(meetingId: Long, slackUserId: String, now: Instant): Boolean

    fun touchForUser(userId: String, meetingIds: Collection<Long>, now: Instant): Int

    fun findDue(now: Instant, limit: Int): List<Long>

    fun claim(id: Long, token: String, now: Instant): MeetingCalendarEvent?

    fun find(id: Long): MeetingCalendarEvent?

    fun markSynced(
        id: Long,
        token: String,
        observedSeq: Long,
        googleEventId: String,
        now: Instant,
    ): Boolean

    fun deleteSynced(id: Long, token: String, observedSeq: Long): Boolean

    fun releaseWithoutEvent(id: Long, token: String, now: Instant): Boolean

    fun retryLater(
        id: Long,
        token: String,
        observedSeq: Long,
        attempts: Int,
        nextAttemptAt: Instant,
        lastError: String,
        now: Instant,
    ): Boolean

    fun markFailed(
        id: Long,
        token: String,
        observedSeq: Long,
        attempts: Int,
        lastError: String,
        now: Instant,
    ): CalendarSyncStatus?

    fun resetStuck(olderThan: Instant, now: Instant): Int

    fun failPendingForUser(userId: String, lastError: String, now: Instant): Int

    fun loadSyncView(meetingId: Long): CalendarMeetingView?
}
