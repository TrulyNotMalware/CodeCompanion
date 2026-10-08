package dev.notypie.application.service.calendar

import dev.notypie.repository.calendar.GoogleCalendarConnectionRepository
import dev.notypie.repository.calendar.MeetingCalendarEventRepository
import dev.notypie.repository.meeting.MeetingRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.util.UUID

private val log = KotlinLogging.logger {}

interface MeetingCalendarMirror {
    fun onMeetingCreated(meetingIdempotencyKey: UUID, hostId: String)

    fun onAttendanceChanged(meetingIdempotencyKey: UUID, userId: String, attending: Boolean)

    fun onMeetingCanceled(meetingUid: UUID)

    fun onMeetingRescheduled(meetingId: Long)
}

object NoopMeetingCalendarMirror : MeetingCalendarMirror {
    override fun onMeetingCreated(meetingIdempotencyKey: UUID, hostId: String) = Unit

    override fun onAttendanceChanged(meetingIdempotencyKey: UUID, userId: String, attending: Boolean) = Unit

    override fun onMeetingCanceled(meetingUid: UUID) = Unit

    override fun onMeetingRescheduled(meetingId: Long) = Unit
}

class MeetingCalendarMirrorService(
    private val queue: MeetingCalendarEventRepository,
    private val connections: GoogleCalendarConnectionRepository,
    private val meetings: MeetingRepository,
    private val clock: Clock,
) : MeetingCalendarMirror {
    override fun onMeetingCreated(meetingIdempotencyKey: UUID, hostId: String) {
        checkInsideMeetingWrite(hook = "onMeetingCreated")
        if (!connections.hasActiveConnection(userId = hostId)) return
        val meetingId = resolveMeetingId(meetingIdempotencyKey = meetingIdempotencyKey) ?: return
        queue.enqueue(meetingId = meetingId, slackUserId = hostId, now = clock.instant())
    }

    override fun onAttendanceChanged(meetingIdempotencyKey: UUID, userId: String, attending: Boolean) {
        checkInsideMeetingWrite(hook = "onAttendanceChanged")
        if (attending && !connections.hasActiveConnection(userId = userId)) return
        val meetingId = resolveMeetingId(meetingIdempotencyKey = meetingIdempotencyKey) ?: return
        if (attending) {
            queue.enqueue(meetingId = meetingId, slackUserId = userId, now = clock.instant())
        } else {
            queue.touchOne(meetingId = meetingId, slackUserId = userId, now = clock.instant())
        }
    }

    override fun onMeetingCanceled(meetingUid: UUID) {
        checkInsideMeetingWrite(hook = "onMeetingCanceled")
        queue.touchMeetingByUid(meetingUid = meetingUid, now = clock.instant())
    }

    override fun onMeetingRescheduled(meetingId: Long) {
        checkInsideMeetingWrite(hook = "onMeetingRescheduled")
        queue.touchMeeting(meetingId = meetingId, now = clock.instant())
    }

    private fun checkInsideMeetingWrite(hook: String) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "MeetingCalendarMirror.$hook must join the meeting write's transaction"
        }
    }

    private fun resolveMeetingId(meetingIdempotencyKey: UUID): Long? {
        val meetingId = meetings.findMeetingId(idempotencyKey = meetingIdempotencyKey)
        if (meetingId == null) {
            log.warn { "Calendar mirror hook found no meeting: meetingIdempotencyKey=$meetingIdempotencyKey" }
        }
        return meetingId
    }
}
