package dev.notypie.application.service.meeting

import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.AddParticipantEvent
import dev.notypie.domain.command.entity.event.AddParticipantPayload
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.DeclineModalOpenFailedEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendanceEvent
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.meet.createCancelMeetingEvent
import dev.notypie.domain.meet.createGetMeetingListEvent
import dev.notypie.domain.meet.createMeetingDto
import dev.notypie.domain.meet.createUpdateMeetingAttendanceEvent
import dev.notypie.domain.meet.entity.RejectReason
import dev.notypie.impl.command.event.MessageType
import dev.notypie.impl.command.event.createSendSlackMessageEvent
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.meeting.AddParticipantResult
import dev.notypie.repository.meeting.MeetingRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
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
        val stager = mockk<OutboundMessageStager>()
        val eventPublisher = mockk<EventPublisher>(relaxed = true)
        val service =
            MeetingServiceImpl(
                meetingRepository = meetingRepository,
                retryService = retryService,
                commandExecutor = commandExecutor,
                outboundStager = stager,
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
            val capturedMessage = slot<OutboundMessage>()
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
                every { stager.stage(message = capture(capturedMessage), basicInfo = any()) } returns ephemeralEvent

                val captured = slot<EventQueue<CommandEvent<EventPayload>>>()
                every { eventPublisher.publishEvent(events = capture(captured)) } returns Unit

                service.cancelMeeting(event = event)

                then("publishes a success ephemeral targeted at the requester") {
                    val published = captured.captured.toList()
                    published.size shouldBe 1
                    published.single() shouldBe ephemeralEvent
                    verify(exactly = 1) {
                        stager.stage(
                            message =
                                OutboundMessage.Ephemeral(
                                    target = ConversationTarget(id = basic.channel),
                                    recipient = UserRef(id = requesterId),
                                    content = MessageContent.Text(headline = null, markdown = "Meeting canceled."),
                                    detailType = CommandDetailType.CANCEL_MEETING,
                                ),
                            basicInfo = basic,
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
                every { stager.stage(message = capture(capturedMessage), basicInfo = any()) } returns ephemeralEvent
                every { eventPublisher.publishEvent(events = any()) } returns Unit

                service.cancelMeeting(event = event)

                then("publishes a friendly already-canceled-or-non-host ephemeral instead of throwing") {
                    val ephemeral = capturedMessage.captured as OutboundMessage.Ephemeral
                    val body = (ephemeral.content as MessageContent.Text).markdown
                    body shouldBe "Meeting was already canceled, or you are not the host."
                }
            }

            `when`("the repository throws an unexpected error") {
                every {
                    meetingRepository.markMeetingCanceled(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                    )
                } throws RuntimeException("db down")
                every { stager.stage(message = capture(capturedMessage), basicInfo = any()) } returns ephemeralEvent
                every { eventPublisher.publishEvent(events = any()) } returns Unit

                service.cancelMeeting(event = event)

                then("the listener swallows the failure and surfaces a retry-later ephemeral") {
                    val ephemeral = capturedMessage.captured as OutboundMessage.Ephemeral
                    val body = (ephemeral.content as MessageContent.Text).markdown
                    body shouldBe "Failed to cancel the meeting. Please try again later."
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
                every { stager.stage(message = any(), basicInfo = any()) } returns ephemeralEvent

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

        given("addParticipants receives an AddParticipantEvent") {
            val meetingUid = UUID.randomUUID()
            val requesterId = "U_HOST_ADD"
            val basic = createCommandBasicInfo()
            val event =
                AddParticipantEvent(
                    idempotencyKey = basic.idempotencyKey,
                    payload =
                        AddParticipantPayload(
                            meetingUid = meetingUid,
                            requesterId = requesterId,
                            participantUserIds = listOf("U_A", "U_B"),
                            responseBasicInfo = basic,
                        ),
                    type = CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
                )
            val ephemeralEvent =
                createSendSlackMessageEvent(
                    commandDetailType = CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
                    idempotencyKey = basic.idempotencyKey,
                    messageType = MessageType.EPHEMERAL_MESSAGE,
                )

            `when`("the repository reports the users were added") {
                every {
                    meetingRepository.addParticipants(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        participantUserIds = listOf("U_A", "U_B"),
                    )
                } returns
                    AddParticipantResult(
                        outcome = AddParticipantResult.Outcome.ADDED,
                        addedUserIds = listOf("U_A", "U_B"),
                        meeting = createMeetingDto(creator = requesterId, title = "Team Sync"),
                    )
                every { stager.stage(message = any(), basicInfo = any()) } returns ephemeralEvent

                service.addParticipants(event = event)

                then("each added user gets the Accept/Decline approval notice") {
                    verify(exactly = 1) {
                        stager.stage(
                            message =
                                match {
                                    it is OutboundMessage.Approval &&
                                        it.recipient == UserRef(id = "U_A") &&
                                        it.approval.commandDetailType == CommandDetailType.MEETING_APPROVAL_REQUEST
                                },
                            basicInfo = any(),
                        )
                    }
                    verify(exactly = 1) {
                        stager.stage(
                            message =
                                match {
                                    it is OutboundMessage.Approval &&
                                        it.recipient == UserRef(id = "U_B") &&
                                        it.approval.commandDetailType == CommandDetailType.MEETING_APPROVAL_REQUEST
                                },
                            basicInfo = any(),
                        )
                    }
                }

                then("the host gets an in-channel confirmation mentioning the added users") {
                    verify(exactly = 1) {
                        stager.stage(
                            message =
                                match { message ->
                                    message is OutboundMessage.Ephemeral &&
                                        message.recipient == UserRef(id = requesterId) &&
                                        message.detailType == CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT &&
                                        message.content.let {
                                            it is MessageContent.Text &&
                                                it.markdown == "Added <@U_A> <@U_B> to the meeting."
                                        }
                                },
                            basicInfo = basic,
                        )
                    }
                }
            }

            `when`("the repository rejects a non-host requester") {
                // The ADDED branch above already recorded stage calls on this shared mock; clear them
                // so the exactly-0 verification below counts only this branch.
                clearMocks(stager)
                val capturedMessage = slot<OutboundMessage>()
                every {
                    meetingRepository.addParticipants(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        participantUserIds = listOf("U_A", "U_B"),
                    )
                } returns AddParticipantResult(outcome = AddParticipantResult.Outcome.NOT_AUTHORIZED)
                every { stager.stage(message = capture(capturedMessage), basicInfo = any()) } returns ephemeralEvent

                service.addParticipants(event = event)

                then("no approval notice is sent and the host sees a not-authorized ephemeral") {
                    verify(exactly = 0) {
                        stager.stage(message = match { it is OutboundMessage.Approval }, basicInfo = any())
                    }
                    val ephemeral = capturedMessage.captured as OutboundMessage.Ephemeral
                    val body = (ephemeral.content as MessageContent.Text).markdown
                    body shouldBe "Meeting was canceled, or you are not the host."
                }
            }
        }

        given("getMeetingListEvent receives a GetMeetingListEvent") {
            val basic = createCommandBasicInfo()
            val event =
                createGetMeetingListEvent(
                    publisherId = basic.publisherId,
                    idempotencyKey = basic.idempotencyKey,
                    responseBasicInfo = basic,
                )
            val payload = event.payload
            val target = ConversationTarget(id = basic.channel)
            val ephemeralEvent =
                createSendSlackMessageEvent(
                    commandDetailType = CommandDetailType.GET_MEETING_LIST,
                    idempotencyKey = basic.idempotencyKey,
                    messageType = MessageType.EPHEMERAL_MESSAGE,
                )

            `when`("the repository returns the user's meetings") {
                val meetings =
                    listOf(
                        createMeetingDto(creator = basic.publisherId, title = "Team Sync"),
                        createMeetingDto(creator = "U_OTHER", title = "Design Review"),
                    )
                every {
                    meetingRepository.getMeetingsByUserIdInRange(
                        userId = payload.publisherId,
                        startAt = payload.startDate,
                        endAt = payload.endDate,
                    )
                } returns meetings
                every { stager.stage(message = any(), basicInfo = any()) } returns ephemeralEvent

                val captured = slot<EventQueue<CommandEvent<EventPayload>>>()
                every { eventPublisher.publishEvent(events = capture(captured)) } returns Unit

                service.getMeetingListEvent(event = event)

                then("stages a MeetingList ephemeral scoped to the caller and publishes it") {
                    verify(exactly = 1) {
                        stager.stage(
                            message =
                                OutboundMessage.Ephemeral(
                                    target = target,
                                    content =
                                        MessageContent.MeetingList(
                                            meetings = meetings,
                                            currentUserId = payload.responseBasicInfo.publisherId,
                                        ),
                                ),
                            basicInfo = payload.responseBasicInfo,
                        )
                    }
                    val published = captured.captured.toList()
                    published.size shouldBe 1
                    published.single() shouldBe ephemeralEvent
                }
            }

            `when`("the repository throws") {
                every {
                    meetingRepository.getMeetingsByUserIdInRange(
                        userId = payload.publisherId,
                        startAt = payload.startDate,
                        endAt = payload.endDate,
                    )
                } throws RuntimeException("db down")
                every { stager.stage(message = any(), basicInfo = any()) } returns ephemeralEvent

                val captured = slot<EventQueue<CommandEvent<EventPayload>>>()
                every { eventPublisher.publishEvent(events = capture(captured)) } returns Unit

                service.getMeetingListEvent(event = event)

                then("stages an ERROR_RESPONSE retry-later ephemeral and publishes it") {
                    verify(exactly = 1) {
                        stager.stage(
                            message =
                                OutboundMessage.Ephemeral(
                                    target = target,
                                    content =
                                        MessageContent.Text(
                                            headline = null,
                                            markdown = "Failed to fetch your meetings. Please try again later.",
                                        ),
                                    detailType = CommandDetailType.ERROR_RESPONSE,
                                ),
                            basicInfo = payload.responseBasicInfo,
                        )
                    }
                    val published = captured.captured.toList()
                    published.size shouldBe 1
                    published.single() shouldBe ephemeralEvent
                }
            }
        }
    })
