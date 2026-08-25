package dev.notypie.application.service.meeting

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.meet.createMeetingDto
import dev.notypie.domain.meet.createMeetingParticipantDto
import dev.notypie.domain.meet.createRescheduleMeetingEvent
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
        val stager = mockk<OutboundMessageStager>()
        val eventPublisher = mockk<EventPublisher>(relaxed = true)
        val service =
            MeetingRescheduleService(
                meetingRepository = meetingRepository,
                reminderRepository = reminderRepository,
                outboundStager = stager,
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

            `when`("the repository confirms the reschedule succeeded") {
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
                every { stager.stage(message = any(), basicInfo = any()) } returns ephemeralEvent

                service.rescheduleMeeting(event = event)

                then("the meeting's reminders are cleared so the scheduler re-arms them") {
                    verify(exactly = 1) { reminderRepository.deleteByMeetingId(meetingId = meetingId) }
                }

                then("a participant re-notification is published") {
                    verify(exactly = 1) {
                        stager.stage(
                            message =
                                OutboundMessage.ChannelMessage(
                                    target = ConversationTarget(id = basic.channel),
                                    content =
                                        MessageContent.Text(
                                            headline = "Meeting rescheduled",
                                            markdown =
                                                "[Notice] <@U_P1> <@U_P2> *Test Meeting* has been rescheduled to " +
                                                    "2026-07-01 14:30.",
                                        ),
                                    detailType = CommandDetailType.MEETING_RESCHEDULE_SUBMIT,
                                ),
                            basicInfo = basic,
                        )
                    }
                }

                then("a success ephemeral is published to the requester") {
                    verify(exactly = 1) {
                        stager.stage(
                            message =
                                OutboundMessage.Ephemeral(
                                    target = ConversationTarget(id = basic.channel),
                                    recipient = UserRef(id = requesterId),
                                    content =
                                        MessageContent.Text(
                                            headline = null,
                                            markdown = "Meeting rescheduled to 2026-07-01 14:30.",
                                        ),
                                    detailType = CommandDetailType.MEETING_RESCHEDULE_SUBMIT,
                                ),
                            basicInfo = basic,
                        )
                    }
                }
            }

            `when`("the repository reports a no-op (non-host or canceled)") {
                // Fresh mocks isolate call counts from the happy-path scenario above, which shares
                // the spec-scope relaxed mocks (kotest accumulates verify counts across when-blocks).
                val localMeetingRepository = mockk<MeetingRepository>()
                val localReminderRepository = mockk<MeetingReminderRepository>(relaxed = true)
                val localStager = mockk<OutboundMessageStager>()
                val localEventPublisher = mockk<EventPublisher>(relaxed = true)
                val localService =
                    MeetingRescheduleService(
                        meetingRepository = localMeetingRepository,
                        reminderRepository = localReminderRepository,
                        outboundStager = localStager,
                        eventPublisher = localEventPublisher,
                    )
                val capturedMessage = slot<OutboundMessage>()
                every {
                    localMeetingRepository.rescheduleMeeting(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        newStartAt = newStartAt,
                    )
                } returns false
                every {
                    localStager.stage(message = capture(capturedMessage), basicInfo = any())
                } returns ephemeralEvent

                localService.rescheduleMeeting(event = event)

                then("a friendly non-host-or-canceled ephemeral is published instead of throwing") {
                    val ephemeral = capturedMessage.captured as OutboundMessage.Ephemeral
                    val body = (ephemeral.content as MessageContent.Text).markdown
                    body shouldBe "Meeting was canceled, or you are not the host."
                }

                then("reminders are NOT cleared and no re-notification is published") {
                    verify(exactly = 0) { localReminderRepository.deleteByMeetingId(any()) }
                    verify(exactly = 0) { localMeetingRepository.findMeetingByUid(any()) }
                    verify(exactly = 0) {
                        localStager.stage(
                            message = match { it is OutboundMessage.ChannelMessage },
                            basicInfo = any(),
                        )
                    }
                }
            }

            `when`("the repository throws an unexpected error") {
                val localMeetingRepository = mockk<MeetingRepository>()
                val localReminderRepository = mockk<MeetingReminderRepository>(relaxed = true)
                val localStager = mockk<OutboundMessageStager>()
                val localEventPublisher = mockk<EventPublisher>(relaxed = true)
                val localService =
                    MeetingRescheduleService(
                        meetingRepository = localMeetingRepository,
                        reminderRepository = localReminderRepository,
                        outboundStager = localStager,
                        eventPublisher = localEventPublisher,
                    )
                val capturedMessage = slot<OutboundMessage>()
                every {
                    localMeetingRepository.rescheduleMeeting(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        newStartAt = newStartAt,
                    )
                } throws RuntimeException("db down")
                every {
                    localStager.stage(message = capture(capturedMessage), basicInfo = any())
                } returns ephemeralEvent

                localService.rescheduleMeeting(event = event)

                then("the listener swallows the failure and surfaces a retry-later ephemeral") {
                    val ephemeral = capturedMessage.captured as OutboundMessage.Ephemeral
                    val body = (ephemeral.content as MessageContent.Text).markdown
                    body shouldBe "Failed to reschedule the meeting. Please try again later."
                    verify(exactly = 0) { localReminderRepository.deleteByMeetingId(any()) }
                }
            }
        }
    })
