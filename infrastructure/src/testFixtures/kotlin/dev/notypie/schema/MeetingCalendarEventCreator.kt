package dev.notypie.schema

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.repository.calendar.CalendarMeetingView
import dev.notypie.repository.calendar.schema.CalendarSyncStatus
import dev.notypie.repository.calendar.schema.MeetingCalendarEvent
import dev.notypie.repository.calendar.schema.MeetingCalendarEventSchema
import dev.notypie.repository.meeting.schema.MeetingSchema
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

fun createMeetingCalendarEventSchema(
    meeting: MeetingSchema,
    slackUserId: String = TEST_USER_ID,
    status: CalendarSyncStatus = CalendarSyncStatus.PENDING,
    claimToken: String? = null,
    nextAttemptAt: Instant = Instant.parse("2031-01-01T00:00:00Z"),
) = MeetingCalendarEventSchema(
    meeting = meeting,
    slackUserId = slackUserId,
    status = status,
    claimToken = claimToken,
    nextAttemptAt = nextAttemptAt,
)

fun createMeetingCalendarEvent(
    id: Long = 1L,
    meetingId: Long = 1L,
    slackUserId: String = TEST_USER_ID,
    googleEventId: String? = null,
    status: CalendarSyncStatus = CalendarSyncStatus.PENDING,
    changeSeq: Long = 1L,
    attempts: Int = 0,
    nextAttemptAt: Instant = Instant.parse("2031-01-01T00:00:00Z"),
    lastError: String? = null,
) = MeetingCalendarEvent(
    id = id,
    meetingId = meetingId,
    slackUserId = slackUserId,
    googleEventId = googleEventId,
    status = status,
    changeSeq = changeSeq,
    attempts = attempts,
    nextAttemptAt = nextAttemptAt,
    lastError = lastError,
)

fun createCalendarMeetingView(
    meetingId: Long = 1L,
    meetingUid: UUID = UUID.fromString("7d2c3f9e-4b1a-4c55-9a8e-0f6b2d1c3e4a"),
    title: String = "test meeting schema",
    reason: String = "",
    startAt: LocalDateTime = LocalDateTime.of(2031, 1, 1, 10, 0),
    endAt: LocalDateTime = startAt.plusHours(1L),
    isCanceled: Boolean = false,
    hostId: String = TEST_USER_ID,
    attendingUserIds: Set<String> = emptySet(),
) = CalendarMeetingView(
    meetingId = meetingId,
    meetingUid = meetingUid,
    title = title,
    reason = reason,
    startAt = startAt,
    endAt = endAt,
    isCanceled = isCanceled,
    hostId = hostId,
    attendingUserIds = attendingUserIds,
)
