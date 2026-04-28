package dev.notypie.application.service.meeting

import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createSendSlackMessageEvent
import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.DeclineModalOpenFailedEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.MessageType
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendanceEvent
import dev.notypie.domain.meet.createCancelMeetingEvent
import dev.notypie.domain.meet.createUpdateMeetingAttendanceEvent
import dev.notypie.impl.command.SlackApiEventConstructor
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.meeting.MeetingRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID

class MeetingServiceImplTest :
    BehaviorSpec({
        val meetingRepository = mockk<MeetingRepository>()
        val retryService = mockk<RetryService>()
        val commandExecutor = mockk<CommandExecutor>()
        val slackEventBuilder = mockk<SlackApiEventConstructor>()
        val eventPublisher = mockk<EventPublisher>(relaxed = true)
        val service =
            MeetingServiceImpl(
                meetingRepository = meetingRepository,
                retryService = retryService,
                commandExecutor = commandExecutor,
                slackEventBuilder = slackEventBuilder,
                eventPublisher = eventPublisher,
            )

        // Passthrough: call the action directly so we can assert behavior without mocking retries.
        every { retryService.execute<Int>(action = any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            firstArg<() -> Int>().invoke()
        }

        given("updateParticipantAttendance receives an UpdateMeetingAttendanceEvent") {
            val meetingKey = UUID.randomUUID()
            val participantUserId = "U_PARTICIPANT"
            val event =
                createUpdateMeetingAttendanceEvent(
                    meetingIdempotencyKey = meetingKey,
                    participantUserId = participantUserId,
                )

            `when`("the UPDATE affects a single row") {
                every {
                    meetingRepository.updateParticipantAttendance(
                        meetingIdempotencyKey = meetingKey,
                        userId = participantUserId,
                        isAttending = false,
                        absentReason = RejectReason.OTHER,
                    )
                } returns 1

                then("the call completes without consulting participantExists") {
                    service.updateParticipantAttendance(event = event)
                    verify(exactly = 0) { meetingRepository.participantExists(any(), any()) }
                }
            }

            `when`("the UPDATE affects zero rows but the participant row exists (MariaDB no-op)") {
                every {
                    meetingRepository.updateParticipantAttendance(
                        meetingIdempotencyKey = meetingKey,
                        userId = participantUserId,
                        isAttending = false,
                        absentReason = RejectReason.OTHER,
                    )
                } returns 0
                every {
                    meetingRepository.participantExists(meetingIdempotencyKey = meetingKey, userId = participantUserId)
                } returns true

                then("the listener swallows the no-op so the transaction still commits") {
                    service.updateParticipantAttendance(event = event)
                    verify(exactly = 1) {
                        meetingRepository.participantExists(
                            meetingIdempotencyKey = meetingKey,
                            userId = participantUserId,
                        )
                    }
                }
            }

            `when`("the UPDATE affects zero rows AND the participant row is missing") {
                every {
                    meetingRepository.updateParticipantAttendance(
                        meetingIdempotencyKey = meetingKey,
                        userId = participantUserId,
                        isAttending = false,
                        absentReason = RejectReason.OTHER,
                    )
                } returns 0
                every {
                    meetingRepository.participantExists(meetingIdempotencyKey = meetingKey, userId = participantUserId)
                } returns false

                then("the listener throws so the enclosing transaction rolls back") {
                    shouldThrow<IllegalStateException> {
                        service.updateParticipantAttendance(event = event)
                    }
                }
            }
        }

        given("cancelMeeting receives a CancelMeetingEvent") {
            val meetingUid = UUID.randomUUID()
            val requesterId = "U_HOST_LISTENER"
            val basic = createCommandBasicInfo()
            val event =
                createCancelMeetingEvent(
                    meetingUid = meetingUid,
                    requesterId = requesterId,
                    idempotencyKey = basic.idempotencyKey,
                    responseBasicInfo = basic,
                )
            val capturedTextSlot = slot<String>()
            val ephemeralEvent =
                createSendSlackMessageEvent(
                    commandDetailType = CommandDetailType.CANCEL_MEETING,
                    idempotencyKey = basic.idempotencyKey,
                    messageType = MessageType.EPHEMERAL_MESSAGE,
                )

            `when`("the repository confirms the cancel succeeded") {
                every {
                    meetingRepository.markMeetingCanceled(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                    )
                } returns true
                every {
                    slackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = capture(capturedTextSlot),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        targetUserId = any(),
                    )
                } returns ephemeralEvent

                val captured = slot<EventQueue<CommandEvent<EventPayload>>>()
                every { eventPublisher.publishEvent(events = capture(captured)) } returns Unit

                service.cancelMeeting(event = event)

                then("publishes a success ephemeral targeted at the requester") {
                    capturedTextSlot.captured shouldBe "Meeting canceled."
                    val published = captured.captured.toList()
                    published.size shouldBe 1
                    published.single() shouldBe ephemeralEvent
                    verify(exactly = 1) {
                        slackEventBuilder.simpleEphemeralTextRequest(
                            textMessage = "Meeting canceled.",
                            commandBasicInfo = basic,
                            commandDetailType = CommandDetailType.CANCEL_MEETING,
                            targetUserId = requesterId,
                        )
                    }
                }
            }

            `when`("the repository reports a no-op (non-host or already canceled)") {
                every {
                    meetingRepository.markMeetingCanceled(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                    )
                } returns false
                every {
                    slackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = capture(capturedTextSlot),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        targetUserId = any(),
                    )
                } returns ephemeralEvent
                every { eventPublisher.publishEvent(events = any()) } returns Unit

                service.cancelMeeting(event = event)

                then("publishes a friendly already-canceled-or-non-host ephemeral instead of throwing") {
                    capturedTextSlot.captured shouldBe
                        "Meeting was already canceled, or you are not the host."
                }
            }

            `when`("the repository throws an unexpected error") {
                every {
                    meetingRepository.markMeetingCanceled(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                    )
                } throws RuntimeException("db down")
                every {
                    slackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = capture(capturedTextSlot),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        targetUserId = any(),
                    )
                } returns ephemeralEvent
                every { eventPublisher.publishEvent(events = any()) } returns Unit

                service.cancelMeeting(event = event)

                then("the listener swallows the failure and surfaces a retry-later ephemeral") {
                    capturedTextSlot.captured shouldBe
                        "Failed to cancel the meeting. Please try again later."
                }
            }
        }

        given("onDeclineModalOpenFailed receives a DeclineModalOpenFailedEvent") {
            val meetingKey = UUID.randomUUID()
            val basic = createCommandBasicInfo()
            val failedEvent =
                DeclineModalOpenFailedEvent(
                    meetingIdempotencyKey = meetingKey,
                    participantUserId = basic.publisherId,
                    apiAppId = basic.appId,
                    channel = basic.channel,
                    idempotencyKey = basic.idempotencyKey,
                    reason = "trigger_id expired",
                )
            val ephemeralEvent =
                createSendSlackMessageEvent(
                    commandDetailType = CommandDetailType.SIMPLE_TEXT,
                    idempotencyKey = basic.idempotencyKey,
                    messageType = MessageType.EPHEMERAL_MESSAGE,
                )

            `when`("invoked") {
                every {
                    slackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = any(),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        targetUserId = any(),
                    )
                } returns ephemeralEvent

                val captured = slot<EventQueue<CommandEvent<EventPayload>>>()
                every { eventPublisher.publishEvent(events = capture(captured)) } returns Unit

                service.onDeclineModalOpenFailed(event = failedEvent)

                then("only an ephemeral notice is published — persistence is NOT re-published") {
                    val published = captured.captured.toList()
                    published.size shouldBe 1
                    published.single() shouldBe ephemeralEvent
                    // Provisional OTHER was already emitted by handleDecline; re-publishing here
                    // would trigger the MariaDB no-op race documented in
                    // updateParticipantAttendance.
                    published.any { it is UpdateMeetingAttendanceEvent } shouldBe false
                }
            }
        }
    })
