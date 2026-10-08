package dev.notypie.application.service.calendar

import dev.notypie.application.service.meeting.createH2TransactionManager
import dev.notypie.repository.calendar.GoogleCalendarConnectionRepository
import dev.notypie.repository.calendar.MeetingCalendarEventRepository
import dev.notypie.repository.meeting.MeetingRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class MeetingCalendarMirrorServiceTest :
    BehaviorSpec({
        val now = Instant.parse("2026-10-08T01:00:00Z")
        val meetingKey = UUID.fromString("3f1d2c4b-6a5e-4d7c-8b9a-0e1f2a3b4c5d")
        val meetingUid = UUID.fromString("7d2c3f9e-4b1a-4c55-9a8e-0f6b2d1c3e4a")
        val meetingId = 42L
        val meetingWrite = TransactionTemplate(createH2TransactionManager())

        class Harness(
            connectedUsers: Set<String>,
            knownMeetingId: Long?,
        ) {
            val queue = mockk<MeetingCalendarEventRepository>()
            val connections = mockk<GoogleCalendarConnectionRepository>()
            val meetings = mockk<MeetingRepository>()
            val mirror =
                MeetingCalendarMirrorService(
                    queue = queue,
                    connections = connections,
                    meetings = meetings,
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                )

            init {
                every { connections.hasActiveConnection(userId = any()) } answers
                    { firstArg<String>() in connectedUsers }
                every { meetings.findMeetingId(idempotencyKey = meetingKey) } returns knownMeetingId
                every { queue.enqueue(meetingId = any(), slackUserId = any(), now = any()) } just Runs
                every { queue.touchOne(meetingId = any(), slackUserId = any(), now = any()) } returns false
                every { queue.touchMeetingByUid(meetingUid = any(), now = any()) } returns 2
                every { queue.touchMeeting(meetingId = any(), now = any()) } returns 2
            }

            fun verifyNoQueueWrite() {
                verify(exactly = 0) { queue.enqueue(meetingId = any(), slackUserId = any(), now = any()) }
                verify(exactly = 0) { queue.touchOne(meetingId = any(), slackUserId = any(), now = any()) }
            }
        }

        fun inMeetingWrite(hook: () -> Unit) = meetingWrite.executeWithoutResult { hook() }

        given("a new meeting") {
            `when`("the host has an active Google connection") {
                val h = Harness(connectedUsers = setOf("U_HOST"), knownMeetingId = meetingId)
                inMeetingWrite { h.mirror.onMeetingCreated(meetingIdempotencyKey = meetingKey, hostId = "U_HOST") }

                then("the host's row is queued once for that meeting at the clock's instant") {
                    verifyOrder {
                        h.connections.hasActiveConnection(userId = "U_HOST")
                        h.meetings.findMeetingId(idempotencyKey = meetingKey)
                        h.queue.enqueue(meetingId = meetingId, slackUserId = "U_HOST", now = now)
                    }
                    verify(exactly = 1) { h.connections.hasActiveConnection(userId = any()) }
                    verify(exactly = 1) { h.meetings.findMeetingId(idempotencyKey = any()) }
                    verify(exactly = 1) { h.queue.enqueue(meetingId = any(), slackUserId = any(), now = any()) }
                }
            }

            `when`("the host never connected Google") {
                val h = Harness(connectedUsers = emptySet(), knownMeetingId = meetingId)
                inMeetingWrite { h.mirror.onMeetingCreated(meetingIdempotencyKey = meetingKey, hostId = "U_HOST") }

                then("nothing is queued and the meeting id is not even looked up") {
                    h.verifyNoQueueWrite()
                    verify(exactly = 0) { h.meetings.findMeetingId(idempotencyKey = any()) }
                }
            }

            `when`("the meeting row cannot be found by its key") {
                val h = Harness(connectedUsers = setOf("U_HOST"), knownMeetingId = null)
                inMeetingWrite { h.mirror.onMeetingCreated(meetingIdempotencyKey = meetingKey, hostId = "U_HOST") }

                then("nothing is queued") {
                    h.verifyNoQueueWrite()
                }
            }
        }

        given("an attendance decision") {
            `when`("a connected participant accepts") {
                val h = Harness(connectedUsers = setOf("U_GUEST"), knownMeetingId = meetingId)
                inMeetingWrite {
                    h.mirror.onAttendanceChanged(
                        meetingIdempotencyKey = meetingKey,
                        userId = "U_GUEST",
                        attending = true,
                    )
                }

                then("the participant's row is queued and no existing row is merely touched") {
                    verify(exactly = 1) { h.queue.enqueue(meetingId = meetingId, slackUserId = "U_GUEST", now = now) }
                    verify(exactly = 0) { h.queue.touchOne(meetingId = any(), slackUserId = any(), now = any()) }
                }
            }

            `when`("a participant without a Google connection accepts") {
                val h = Harness(connectedUsers = emptySet(), knownMeetingId = meetingId)
                inMeetingWrite {
                    h.mirror.onAttendanceChanged(
                        meetingIdempotencyKey = meetingKey,
                        userId = "U_GUEST",
                        attending = true,
                    )
                }

                then("nothing is written and the meeting id is not looked up") {
                    h.verifyNoQueueWrite()
                    verify(exactly = 0) { h.meetings.findMeetingId(idempotencyKey = any()) }
                }
            }

            `when`("a participant declines") {
                val h = Harness(connectedUsers = setOf("U_GUEST"), knownMeetingId = meetingId)
                inMeetingWrite {
                    h.mirror.onAttendanceChanged(
                        meetingIdempotencyKey = meetingKey,
                        userId = "U_GUEST",
                        attending = false,
                    )
                }

                then("only an existing row is touched, never inserted, whatever the connection state") {
                    verify(exactly = 1) { h.queue.touchOne(meetingId = meetingId, slackUserId = "U_GUEST", now = now) }
                    verify(exactly = 0) { h.queue.enqueue(meetingId = any(), slackUserId = any(), now = any()) }
                    verify(exactly = 0) { h.connections.hasActiveConnection(userId = any()) }
                }
            }

            `when`("the decision names a meeting that cannot be found") {
                val h = Harness(connectedUsers = setOf("U_GUEST"), knownMeetingId = null)
                inMeetingWrite {
                    h.mirror.onAttendanceChanged(
                        meetingIdempotencyKey = meetingKey,
                        userId = "U_GUEST",
                        attending = false,
                    )
                    h.mirror.onAttendanceChanged(
                        meetingIdempotencyKey = meetingKey,
                        userId = "U_GUEST",
                        attending = true,
                    )
                }

                then("nothing is written") {
                    h.verifyNoQueueWrite()
                }
            }
        }

        given("a canceled meeting") {
            val h = Harness(connectedUsers = emptySet(), knownMeetingId = meetingId)
            inMeetingWrite { h.mirror.onMeetingCanceled(meetingUid = meetingUid) }

            then("every row of the meeting is touched by its uid and nothing else is read or written") {
                verify(exactly = 1) { h.queue.touchMeetingByUid(meetingUid = meetingUid, now = now) }
                verify(exactly = 0) { h.meetings.findMeetingId(idempotencyKey = any()) }
                verify(exactly = 0) { h.connections.hasActiveConnection(userId = any()) }
                h.verifyNoQueueWrite()
            }
        }

        given("a rescheduled meeting") {
            val h = Harness(connectedUsers = emptySet(), knownMeetingId = meetingId)
            inMeetingWrite { h.mirror.onMeetingRescheduled(meetingId = meetingId) }

            then("every row of the meeting is touched by its id and nothing else is read or written") {
                verify(exactly = 1) { h.queue.touchMeeting(meetingId = meetingId, now = now) }
                verify(exactly = 0) { h.queue.touchMeetingByUid(meetingUid = any(), now = any()) }
                verify(exactly = 0) { h.connections.hasActiveConnection(userId = any()) }
                h.verifyNoQueueWrite()
            }
        }

        given("a hook called outside the meeting write's transaction") {
            listOf<Pair<String, (MeetingCalendarMirror) -> Unit>>(
                "onMeetingCreated" to { mirror ->
                    mirror.onMeetingCreated(meetingIdempotencyKey = meetingKey, hostId = "U_HOST")
                },
                "onAttendanceChanged" to { mirror ->
                    mirror.onAttendanceChanged(meetingIdempotencyKey = meetingKey, userId = "U_GUEST", attending = true)
                },
                "onMeetingCanceled" to { mirror -> mirror.onMeetingCanceled(meetingUid = meetingUid) },
                "onMeetingRescheduled" to { mirror -> mirror.onMeetingRescheduled(meetingId = meetingId) },
            ).forEach { (hook, call) ->
                `when`("$hook runs with no transaction") {
                    val h = Harness(connectedUsers = setOf("U_HOST", "U_GUEST"), knownMeetingId = meetingId)
                    val failure = shouldThrow<IllegalStateException> { call(h.mirror) }

                    then("it throws before any repository is called") {
                        failure.message shouldBe "MeetingCalendarMirror.$hook must join the meeting write's transaction"
                        confirmVerified(h.queue, h.connections, h.meetings)
                    }
                }
            }
        }
    })
