package dev.notypie.application.service.meeting

import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.DeclineModalOpenFailedEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.PostEventPayloadContents
import dev.notypie.domain.command.entity.event.SendSlackMessageEvent
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendanceEvent
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendancePayload
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
                UpdateMeetingAttendanceEvent(
                    idempotencyKey = UUID.randomUUID(),
                    payload =
                        UpdateMeetingAttendancePayload(
                            meetingIdempotencyKey = meetingKey,
                            participantUserId = participantUserId,
                            isAttending = false,
                            absentReason = RejectReason.OTHER,
                        ),
                    type = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
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
                SendSlackMessageEvent(
                    idempotencyKey = basic.idempotencyKey,
                    payload =
                        PostEventPayloadContents(
                            eventId = UUID.randomUUID(),
                            apiAppId = basic.appId,
                            messageType = dev.notypie.domain.command.entity.event.MessageType.EPHEMERAL_MESSAGE,
                            commandDetailType = CommandDetailType.SIMPLE_TEXT,
                            idempotencyKey = basic.idempotencyKey,
                            publisherId = basic.publisherId,
                            channel = basic.channel,
                            replaceOriginal = false,
                            body = emptyMap(),
                        ),
                    destination = "",
                    timestamp = System.currentTimeMillis(),
                    type = CommandDetailType.SIMPLE_TEXT,
                )

            `when`("invoked") {
                every {
                    slackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = any(),
                        commandBasicInfo = any(),
                        commandType = any(),
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
