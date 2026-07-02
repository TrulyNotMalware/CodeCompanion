package dev.notypie.application.service.meeting

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.meet.createMeetingDto
import dev.notypie.domain.meet.createMeetingParticipantDto
import dev.notypie.domain.meet.createRescheduleMeetingEvent
import dev.notypie.impl.command.SlackApiEventConstructor
import dev.notypie.impl.command.event.MessageType
import dev.notypie.impl.command.event.createSendSlackMessageEvent
import dev.notypie.repository.meeting.MeetingReminderRepository
import dev.notypie.repository.meeting.MeetingRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDateTime
import java.util.UUID

class MeetingRescheduleServiceTest :
    BehaviorSpec({
        val meetingRepository = mockk<MeetingRepository>()
        val reminderRepository = mockk<MeetingReminderRepository>(relaxed = true)
        val slackEventBuilder = mockk<SlackApiEventConstructor>()
        val eventPublisher = mockk<EventPublisher>(relaxed = true)
        val service =
            MeetingRescheduleService(
                meetingRepository = meetingRepository,
                reminderRepository = reminderRepository,
                slackEventBuilder = slackEventBuilder,
                eventPublisher = eventPublisher,
            )

        given("rescheduleMeeting receives a RescheduleMeetingEvent") {
            val meetingUid = UUID.randomUUID()
            val requesterId = "U_HOST_LISTENER"
            val meetingId = 42L
            val newStartAt = LocalDateTime.of(2026, 7, 1, 14, 30)
            val basic = createCommandBasicInfo()
            val event =
                createRescheduleMeetingEvent(
                    meetingUid = meetingUid,
                    requesterId = requesterId,
                    newStartAt = newStartAt,
                    idempotencyKey = basic.idempotencyKey,
                    responseBasicInfo = basic,
                )
            val ephemeralEvent =
                createSendSlackMessageEvent(
                    commandDetailType = CommandDetailType.MEETING_RESCHEDULE_SUBMIT,
                    idempotencyKey = basic.idempotencyKey,
                    messageType = MessageType.EPHEMERAL_MESSAGE,
                )
            val noticeEvent =
                createSendSlackMessageEvent(
                    commandDetailType = CommandDetailType.MEETING_RESCHEDULE_SUBMIT,
                    idempotencyKey = basic.idempotencyKey,
                )

            `when`("the repository confirms the reschedule succeeded") {
                val capturedEphemeralText = slot<String>()
                every {
                    meetingRepository.rescheduleMeeting(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        newStartAt = newStartAt,
                    )
                } returns true
                every {
                    meetingRepository.findMeetingByUid(meetingUid = meetingUid)
                } returns
                    createMeetingDto(
                        meetingId = meetingId,
                        meetingUid = meetingUid,
                        creator = requesterId,
                        startAt = newStartAt,
                        participants =
                            listOf(
                                createMeetingParticipantDto(userId = "U_P1"),
                                createMeetingParticipantDto(userId = "U_P2"),
                            ),
                    )
                every {
                    slackEventBuilder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        simpleString = any(),
                    )
                } returns noticeEvent
                every {
                    slackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = capture(capturedEphemeralText),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        targetUserId = any(),
                    )
                } returns ephemeralEvent

                service.rescheduleMeeting(event = event)

                then("the meeting's reminders are cleared so the scheduler re-arms them") {
                    verify(exactly = 1) { reminderRepository.deleteByMeetingId(meetingId = meetingId) }
                }

                then("a participant re-notification is published") {
                    verify(exactly = 1) {
                        slackEventBuilder.simpleTextRequest(
                            commandDetailType = CommandDetailType.MEETING_RESCHEDULE_SUBMIT,
                            headLineText = any(),
                            commandBasicInfo = basic,
                            simpleString = any(),
                        )
                    }
                }

                then("a success ephemeral is published to the requester") {
                    capturedEphemeralText.captured shouldBe "Meeting rescheduled to 2026-07-01 14:30."
                    verify(exactly = 1) {
                        slackEventBuilder.simpleEphemeralTextRequest(
                            textMessage = "Meeting rescheduled to 2026-07-01 14:30.",
                            commandBasicInfo = basic,
                            commandDetailType = CommandDetailType.MEETING_RESCHEDULE_SUBMIT,
                            targetUserId = requesterId,
                        )
                    }
                }
            }

            `when`("the repository reports a no-op (non-host or canceled)") {
                // Fresh mocks isolate call counts from the happy-path scenario above, which shares
                // the spec-scope relaxed mocks (kotest accumulates verify counts across when-blocks).
                val localMeetingRepository = mockk<MeetingRepository>()
                val localReminderRepository = mockk<MeetingReminderRepository>(relaxed = true)
                val localSlackEventBuilder = mockk<SlackApiEventConstructor>()
                val localEventPublisher = mockk<EventPublisher>(relaxed = true)
                val localService =
                    MeetingRescheduleService(
                        meetingRepository = localMeetingRepository,
                        reminderRepository = localReminderRepository,
                        slackEventBuilder = localSlackEventBuilder,
                        eventPublisher = localEventPublisher,
                    )
                val capturedEphemeralText = slot<String>()
                every {
                    localMeetingRepository.rescheduleMeeting(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        newStartAt = newStartAt,
                    )
                } returns false
                every {
                    localSlackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = capture(capturedEphemeralText),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        targetUserId = any(),
                    )
                } returns ephemeralEvent

                localService.rescheduleMeeting(event = event)

                then("a friendly non-host-or-canceled ephemeral is published instead of throwing") {
                    capturedEphemeralText.captured shouldBe "Meeting was canceled, or you are not the host."
                }

                then("reminders are NOT cleared and no re-notification is published") {
                    verify(exactly = 0) { localReminderRepository.deleteByMeetingId(any()) }
                    verify(exactly = 0) { localMeetingRepository.findMeetingByUid(any()) }
                    verify(exactly = 0) {
                        localSlackEventBuilder.simpleTextRequest(
                            commandDetailType = any(),
                            headLineText = any(),
                            commandBasicInfo = any(),
                            simpleString = any(),
                        )
                    }
                }
            }

            `when`("the repository throws an unexpected error") {
                val localMeetingRepository = mockk<MeetingRepository>()
                val localReminderRepository = mockk<MeetingReminderRepository>(relaxed = true)
                val localSlackEventBuilder = mockk<SlackApiEventConstructor>()
                val localEventPublisher = mockk<EventPublisher>(relaxed = true)
                val localService =
                    MeetingRescheduleService(
                        meetingRepository = localMeetingRepository,
                        reminderRepository = localReminderRepository,
                        slackEventBuilder = localSlackEventBuilder,
                        eventPublisher = localEventPublisher,
                    )
                val capturedEphemeralText = slot<String>()
                every {
                    localMeetingRepository.rescheduleMeeting(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        newStartAt = newStartAt,
                    )
                } throws RuntimeException("db down")
                every {
                    localSlackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = capture(capturedEphemeralText),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        targetUserId = any(),
                    )
                } returns ephemeralEvent

                localService.rescheduleMeeting(event = event)

                then("the listener swallows the failure and surfaces a retry-later ephemeral") {
                    capturedEphemeralText.captured shouldBe
                        "Failed to reschedule the meeting. Please try again later."
                    verify(exactly = 0) { localReminderRepository.deleteByMeetingId(any()) }
                }
            }
        }
    })
