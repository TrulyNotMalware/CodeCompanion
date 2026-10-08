package dev.notypie.application.service.meeting

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.notypie.application.service.calendar.MeetingCalendarMirror
import dev.notypie.application.service.calendar.NoopMeetingCalendarMirror
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
import dev.notypie.domain.meet.createAddParticipantEvent
import dev.notypie.domain.meet.createCancelMeetingEvent
import dev.notypie.domain.meet.createGetMeetingListEvent
import dev.notypie.domain.meet.createMeeting
import dev.notypie.domain.meet.createMeetingDto
import dev.notypie.domain.meet.createRequestMeetingContextResult
import dev.notypie.domain.meet.createUpdateMeetingAttendanceEvent
import dev.notypie.domain.meet.entity.RejectReason
import dev.notypie.impl.command.SlackOutboundStager
import dev.notypie.impl.command.event.MessageType
import dev.notypie.impl.command.event.createSendSlackMessageEvent
import dev.notypie.repository.createSnapshotIsolationFailure
import dev.notypie.repository.meeting.AddParticipantResult
import dev.notypie.repository.meeting.MeetingRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.slf4j.LoggerFactory
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

class MeetingServiceImplTest :
    BehaviorSpec({
        val meetingRepository = mockk<MeetingRepository>()
        val commandExecutor = mockk<CommandExecutor>()
        val stager = mockk<OutboundMessageStager>()
        val eventPublisher = mockk<EventPublisher>(relaxed = true)
        val twoConnectionPool = createBoundedH2DataSource(maxConnections = 2)
        afterSpec { twoConnectionPool.close() }
        val service =
            MeetingServiceImpl(
                meetingRepository = meetingRepository,
                commandExecutor = commandExecutor,
                outboundStager = stager,
                eventPublisher = eventPublisher,
                transactionManager = createH2TransactionManager(),
                calendarMirror = NoopMeetingCalendarMirror,
            )

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

            `when`("the UPDATE itself fails inside the caller's transaction") {
                clearMocks(meetingRepository, answers = false)
                val failure = DataAccessResourceFailureException("connection lost")
                every {
                    meetingRepository.updateParticipantAttendance(
                        meetingIdempotencyKey = meetingKey,
                        userId = participantUserId,
                        isAttending = false,
                        absentReason = RejectReason.OTHER,
                    )
                } throws failure

                then("the original failure propagates after one attempt, so the transaction rolls back") {
                    shouldThrow<DataAccessResourceFailureException> {
                        service.updateParticipantAttendance(event = event)
                    } shouldBeSameInstanceAs failure
                    verify(exactly = 1) {
                        meetingRepository.updateParticipantAttendance(
                            meetingIdempotencyKey = meetingKey,
                            userId = participantUserId,
                            isAttending = false,
                            absentReason = RejectReason.OTHER,
                        )
                    }
                }
            }
        }

        given("the calendar mirror hooks on the meeting listeners") {
            fun hookedService(repository: MeetingRepository, mirror: MeetingCalendarMirror) =
                MeetingServiceImpl(
                    meetingRepository = repository,
                    commandExecutor = commandExecutor,
                    outboundStager = mockk(relaxed = true),
                    eventPublisher = eventPublisher,
                    transactionManager = createH2TransactionManager(),
                    calendarMirror = mirror,
                )
            val meetingKey = UUID.randomUUID()

            `when`("a new meeting is persisted") {
                val repository = mockk<MeetingRepository>()
                val mirror = mockk<MeetingCalendarMirror>(relaxed = true)
                val result = createRequestMeetingContextResult(meeting = createMeeting(publisher = "U_HOST_HOOK"))
                every {
                    repository.createNewMeeting(
                        meeting = result.meeting,
                        idempotencyKey = result.idempotencyKey,
                        channel = result.commandBasicInfo.channel,
                    )
                } returns result.meeting

                hookedService(repository = repository, mirror = mirror).createNewMeeting(event = result)

                then("the mirror gets the meeting's idempotency key and its host, after the insert") {
                    verifyOrder {
                        repository.createNewMeeting(
                            meeting = result.meeting,
                            idempotencyKey = result.idempotencyKey,
                            channel = result.commandBasicInfo.channel,
                        )
                        mirror.onMeetingCreated(meetingIdempotencyKey = result.idempotencyKey, hostId = "U_HOST_HOOK")
                    }
                    verify(exactly = 1) { mirror.onMeetingCreated(meetingIdempotencyKey = any(), hostId = any()) }
                }
            }

            listOf(true to RejectReason.ATTENDING, false to RejectReason.OTHER).forEach { (attending, reason) ->
                `when`("a participant records attending=$attending") {
                    val repository = mockk<MeetingRepository>()
                    val mirror = mockk<MeetingCalendarMirror>(relaxed = true)
                    every {
                        repository.updateParticipantAttendance(
                            meetingIdempotencyKey = meetingKey,
                            userId = "U_DECIDER",
                            isAttending = attending,
                            absentReason = reason,
                        )
                    } returns 1

                    hookedService(repository = repository, mirror = mirror).updateParticipantAttendance(
                        event =
                            createUpdateMeetingAttendanceEvent(
                                meetingIdempotencyKey = meetingKey,
                                participantUserId = "U_DECIDER",
                                isAttending = attending,
                                absentReason = reason,
                            ),
                    )

                    then("the mirror gets the same decision for that user and meeting") {
                        verify(exactly = 1) {
                            mirror.onAttendanceChanged(
                                meetingIdempotencyKey = meetingKey,
                                userId = "U_DECIDER",
                                attending = attending,
                            )
                        }
                    }
                }
            }

            `when`("the decision repeats the recorded one and the UPDATE reports zero rows") {
                val repository = mockk<MeetingRepository>()
                val mirror = mockk<MeetingCalendarMirror>(relaxed = true)
                every {
                    repository.updateParticipantAttendance(
                        meetingIdempotencyKey = meetingKey,
                        userId = "U_REPEAT",
                        isAttending = true,
                        absentReason = RejectReason.ATTENDING,
                    )
                } returns 0
                every {
                    repository.participantExists(meetingIdempotencyKey = meetingKey, userId = "U_REPEAT")
                } returns true

                hookedService(repository = repository, mirror = mirror).updateParticipantAttendance(
                    event =
                        createUpdateMeetingAttendanceEvent(
                            meetingIdempotencyKey = meetingKey,
                            participantUserId = "U_REPEAT",
                            isAttending = true,
                            absentReason = RejectReason.ATTENDING,
                        ),
                )

                then("the no-op is accepted and the mirror is not called") {
                    verify(exactly = 0) {
                        mirror.onAttendanceChanged(meetingIdempotencyKey = any(), userId = any(), attending = any())
                    }
                }
            }

            `when`("no participant row matches the decision") {
                val repository = mockk<MeetingRepository>()
                val mirror = mockk<MeetingCalendarMirror>(relaxed = true)
                every {
                    repository.updateParticipantAttendance(
                        meetingIdempotencyKey = meetingKey,
                        userId = "U_STRANGER",
                        isAttending = true,
                        absentReason = RejectReason.ATTENDING,
                    )
                } returns 0
                every {
                    repository.participantExists(
                        meetingIdempotencyKey = meetingKey,
                        userId = "U_STRANGER",
                    )
                } returns
                    false

                then("the listener fails before the mirror hears anything") {
                    shouldThrow<IllegalStateException> {
                        hookedService(repository = repository, mirror = mirror).updateParticipantAttendance(
                            event =
                                createUpdateMeetingAttendanceEvent(
                                    meetingIdempotencyKey = meetingKey,
                                    participantUserId = "U_STRANGER",
                                    isAttending = true,
                                    absentReason = RejectReason.ATTENDING,
                                ),
                        )
                    }
                    verify(exactly = 0) {
                        mirror.onAttendanceChanged(meetingIdempotencyKey = any(), userId = any(), attending = any())
                    }
                }
            }

            listOf(true, false).forEach { canceled ->
                `when`("the cancel write returns $canceled") {
                    val repository = mockk<MeetingRepository>()
                    val mirror = mockk<MeetingCalendarMirror>(relaxed = true)
                    val event = createCancelMeetingEvent(requesterId = "U_HOST_HOOK")
                    every {
                        repository.markMeetingCanceled(
                            meetingUid = event.payload.meetingUid,
                            requesterId = "U_HOST_HOOK",
                        )
                    } returns canceled

                    hookedService(repository = repository, mirror = mirror).cancelMeeting(event = event)

                    then("the mirror hears about it only when the meeting was actually canceled") {
                        verify(exactly = if (canceled) 1 else 0) {
                            mirror.onMeetingCanceled(meetingUid = event.payload.meetingUid)
                        }
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

        given("a meeting write loses an optimistic-lock race inside the interaction transaction") {
            val transactionManager = createH2TransactionManager()
            val outerTransaction = TransactionTemplate(transactionManager)
            val recordingPublisher = CommitRecordingEventPublisher()
            val conflictedRepository = mockk<MeetingRepository>()
            val conflictedService =
                MeetingServiceImpl(
                    meetingRepository = conflictedRepository,
                    commandExecutor = commandExecutor,
                    outboundStager = SlackOutboundStager(slackEventBuilder = mockk(), standupRepository = mockk()),
                    eventPublisher = recordingPublisher,
                    transactionManager = transactionManager,
                    calendarMirror = NoopMeetingCalendarMirror,
                )
            val meetingUid = UUID.randomUUID()
            val requesterId = "U_HOST_RACE"
            val basic = createCommandBasicInfo()
            val addEvent =
                AddParticipantEvent(
                    idempotencyKey = basic.idempotencyKey,
                    payload =
                        AddParticipantPayload(
                            meetingUid = meetingUid,
                            requesterId = requesterId,
                            participantUserIds = listOf("U_A"),
                            responseBasicInfo = basic,
                        ),
                    type = CommandDetailType.MEETING_ADD_PARTICIPANT_SUBMIT,
                )

            `when`("the add conflicts once and the retry wins") {
                recordingPublisher.committedMessages.clear()
                every {
                    conflictedRepository.addParticipants(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        participantUserIds = listOf("U_A"),
                    )
                } answers {
                    transactionManager.failInsideParticipatingTx(exception = createMeetingVersionConflict())
                } andThenAnswer {
                    AddParticipantResult(
                        outcome = AddParticipantResult.Outcome.ADDED,
                        addedUserIds = listOf("U_A"),
                        meeting = createMeetingDto(creator = requesterId, title = "Team Sync"),
                    )
                }

                outerTransaction.executeWithoutResult { conflictedService.addParticipants(event = addEvent) }

                then("only the retry's approval notice and confirmation are committed") {
                    verify(exactly = 2) {
                        conflictedRepository.addParticipants(
                            meetingUid = meetingUid,
                            requesterId = requesterId,
                            participantUserIds = listOf("U_A"),
                        )
                    }
                    recordingPublisher.committedMessages.filterIsInstance<OutboundMessage.Approval>().size shouldBe 1
                    recordingPublisher.committedEphemeralMarkdowns shouldBe listOf("Added <@U_A> to the meeting.")
                }
            }

            `when`("the add hits a MariaDB snapshot-isolation conflict once and the retry wins") {
                recordingPublisher.committedMessages.clear()
                clearMocks(conflictedRepository)
                every {
                    conflictedRepository.addParticipants(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        participantUserIds = listOf("U_A"),
                    )
                } answers {
                    transactionManager.failInsideParticipatingTx(
                        exception = createSnapshotIsolationFailure(table = "meetings"),
                    )
                } andThenAnswer {
                    AddParticipantResult(
                        outcome = AddParticipantResult.Outcome.ADDED,
                        addedUserIds = listOf("U_A"),
                        meeting = createMeetingDto(creator = requesterId, title = "Team Sync"),
                    )
                }

                outerTransaction.executeWithoutResult { conflictedService.addParticipants(event = addEvent) }

                then("the write is retried once in a fresh transaction and the host gets the confirmation") {
                    verify(exactly = 2) {
                        conflictedRepository.addParticipants(
                            meetingUid = meetingUid,
                            requesterId = requesterId,
                            participantUserIds = listOf("U_A"),
                        )
                    }
                    recordingPublisher.committedEphemeralMarkdowns shouldBe listOf("Added <@U_A> to the meeting.")
                }
            }

            `when`("the add conflicts on the retry as well") {
                recordingPublisher.committedMessages.clear()
                every {
                    conflictedRepository.addParticipants(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        participantUserIds = listOf("U_A"),
                    )
                } answers { transactionManager.failInsideParticipatingTx(exception = createMeetingVersionConflict()) }

                val escaped =
                    runCatching {
                        outerTransaction.executeWithoutResult { conflictedService.addParticipants(event = addEvent) }
                    }.exceptionOrNull()

                then("nothing escapes the interaction commit and the host gets the try-again reply") {
                    escaped shouldBe null
                    recordingPublisher.committedEphemeralMarkdowns shouldBe
                        listOf("Failed to add participants. Please try again later.")
                }
            }

            `when`("a cancel conflicts on both attempts") {
                recordingPublisher.committedMessages.clear()
                val cancelEvent =
                    createCancelMeetingEvent(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        responseBasicInfo = basic,
                    )
                every {
                    conflictedRepository.markMeetingCanceled(meetingUid = meetingUid, requesterId = requesterId)
                } answers { transactionManager.failInsideParticipatingTx(exception = createMeetingVersionConflict()) }

                val escaped =
                    runCatching {
                        outerTransaction.executeWithoutResult { conflictedService.cancelMeeting(event = cancelEvent) }
                    }.exceptionOrNull()

                then("nothing escapes and the host gets the try-again reply") {
                    escaped shouldBe null
                    verify(exactly = 2) {
                        conflictedRepository.markMeetingCanceled(meetingUid = meetingUid, requesterId = requesterId)
                    }
                    recordingPublisher.committedEphemeralMarkdowns shouldBe
                        listOf("Failed to cancel the meeting. Please try again later.")
                }
            }

            fun stubAdd(first: () -> AddParticipantResult, then: AddParticipantResult) {
                clearMocks(conflictedRepository)
                recordingPublisher.committedMessages.clear()
                every {
                    conflictedRepository.addParticipants(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        participantUserIds = listOf("U_A"),
                    )
                } answers { first() } andThen then
            }

            fun verifyAddAttempts(count: Int) =
                verify(exactly = count) {
                    conflictedRepository.addParticipants(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        participantUserIds = listOf("U_A"),
                    )
                }

            val added =
                AddParticipantResult(
                    outcome = AddParticipantResult.Outcome.ADDED,
                    addedUserIds = listOf("U_A"),
                    meeting = createMeetingDto(creator = requesterId, title = "Team Sync"),
                )

            `when`("the add hits a lock-acquisition failure once") {
                stubAdd(first = { throw CannotAcquireLockException("lock wait timeout") }, then = added)

                outerTransaction.executeWithoutResult { conflictedService.addParticipants(event = addEvent) }

                then("it is retried like a version conflict and the retry's reply is committed") {
                    verifyAddAttempts(count = 2)
                    recordingPublisher.committedEphemeralMarkdowns shouldBe listOf("Added <@U_A> to the meeting.")
                }
            }

            `when`("a concurrent add of the same user wins and the first attempt trips the participant unique key") {
                stubAdd(
                    first = { throw createParticipantDuplicateKeyViolation() },
                    then = AddParticipantResult(outcome = AddParticipantResult.Outcome.NO_NEW_PARTICIPANTS),
                )

                outerTransaction.executeWithoutResult { conflictedService.addParticipants(event = addEvent) }

                then("the retry re-reads the meeting and the host hears nobody new was added") {
                    verifyAddAttempts(count = 2)
                    recordingPublisher.committedEphemeralMarkdowns shouldBe
                        listOf("Those people are already on this meeting.")
                }
            }

            `when`("the add fails on any other integrity violation") {
                stubAdd(first = { throw createNotNullViolation() }, then = added)

                outerTransaction.executeWithoutResult { conflictedService.addParticipants(event = addEvent) }

                then("it is not retried and the host gets the try-again reply") {
                    verifyAddAttempts(count = 1)
                    recordingPublisher.committedEphemeralMarkdowns shouldBe
                        listOf("Failed to add participants. Please try again later.")
                }
            }

            `when`("the add throws an Error") {
                stubAdd(first = { throw OutOfMemoryError("simulated") }, then = added)

                val escaped =
                    runCatching {
                        outerTransaction.executeWithoutResult { conflictedService.addParticipants(event = addEvent) }
                    }.exceptionOrNull()

                then("it propagates instead of turning into a reply, and nothing is committed") {
                    (escaped is OutOfMemoryError) shouldBe true
                    verifyAddAttempts(count = 1)
                    recordingPublisher.committedMessages.size shouldBe 0
                }
            }
        }

        given("a deferred write whose failure reply fails as well") {
            val failingStager = mockk<OutboundMessageStager>()
            every { failingStager.stage(message = any(), basicInfo = any()) } throws
                IllegalStateException("outbox insert failed: database down")
            val downRepository = mockk<MeetingRepository>()
            val downService =
                MeetingServiceImpl(
                    meetingRepository = downRepository,
                    commandExecutor = commandExecutor,
                    outboundStager = failingStager,
                    eventPublisher = eventPublisher,
                    transactionManager = createH2TransactionManager(),
                    calendarMirror = NoopMeetingCalendarMirror,
                )

            `when`("a cancel fails and so does its reply") {
                val writeFailure = RuntimeException("db down")
                val event = createCancelMeetingEvent(requesterId = "U_HOST_DOWN")
                every {
                    downRepository.markMeetingCanceled(
                        meetingUid = event.payload.meetingUid,
                        requesterId = "U_HOST_DOWN",
                    )
                } throws writeFailure

                var escaped: Throwable? = null
                val errors =
                    captureErrorLogs(loggerName = MeetingServiceImpl::class.java.name) {
                        escaped = runCatching { downService.cancelMeeting(event = event) }.exceptionOrNull()
                    }

                then("nothing escapes and one ERROR line carries the reply failure as suppressed") {
                    escaped shouldBe null
                    writeFailure.suppressed.map { it.message } shouldBe listOf("outbox insert failed: database down")
                    errors.map { it.throwableProxy.message } shouldBe listOf("db down")
                }
            }

            `when`("an add fails and so does its reply") {
                val writeFailure = RuntimeException("db down")
                val event = createAddParticipantEvent(requesterId = "U_HOST_DOWN", participantUserIds = listOf("U_A"))
                every {
                    downRepository.addParticipants(
                        meetingUid = event.payload.meetingUid,
                        requesterId = "U_HOST_DOWN",
                        participantUserIds = listOf("U_A"),
                    )
                } throws writeFailure

                var escaped: Throwable? = null
                val errors =
                    captureErrorLogs(loggerName = MeetingServiceImpl::class.java.name) {
                        escaped = runCatching { downService.addParticipants(event = event) }.exceptionOrNull()
                    }

                then("nothing escapes and one ERROR line carries the reply failure as suppressed") {
                    escaped shouldBe null
                    writeFailure.suppressed.map { it.message } shouldBe listOf("outbox insert failed: database down")
                    errors.map { it.throwableProxy.message } shouldBe listOf("db down")
                }
            }
        }

        given("the interaction transaction already holds a connection from a pool of two") {
            val transactionManager = createH2TransactionManager(dataSource = twoConnectionPool)
            val recordingPublisher = CommitRecordingEventPublisher()
            val boundedRepository = mockk<MeetingRepository>()
            val boundedService =
                MeetingServiceImpl(
                    meetingRepository = boundedRepository,
                    commandExecutor = commandExecutor,
                    outboundStager = SlackOutboundStager(slackEventBuilder = mockk(), standupRepository = mockk()),
                    eventPublisher = recordingPublisher,
                    transactionManager = transactionManager,
                    calendarMirror = NoopMeetingCalendarMirror,
                )
            val event = createAddParticipantEvent(requesterId = "U_HOST_POOL", participantUserIds = listOf("U_A"))
            every {
                boundedRepository.addParticipants(
                    meetingUid = event.payload.meetingUid,
                    requesterId = "U_HOST_POOL",
                    participantUserIds = listOf("U_A"),
                )
            } answers {
                transactionManager.failInsideParticipatingTx(exception = createMeetingVersionConflict())
            } andThen
                AddParticipantResult(
                    outcome = AddParticipantResult.Outcome.ADDED,
                    addedUserIds = listOf("U_A"),
                    meeting = createMeetingDto(creator = "U_HOST_POOL"),
                )

            `when`("the isolated write conflicts once and is retried") {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    boundedService.addParticipants(event = event)
                }

                then("the retry never needs a third connection: the first attempt released its own before it ran") {
                    recordingPublisher.committedEphemeralMarkdowns shouldBe listOf("Added <@U_A> to the meeting.")
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

                val failure = runCatching { service.getMeetingListEvent(event = event) }.exceptionOrNull()

                then(
                    "the failure propagates and no reply is staged, since the caller's transaction cannot commit one",
                ) {
                    failure.shouldBeInstanceOf<RuntimeException>().message shouldBe "db down"
                    verify(exactly = 0) {
                        stager.stage(
                            message =
                                match {
                                    it is OutboundMessage.Ephemeral &&
                                        it.detailType == CommandDetailType.ERROR_RESPONSE
                                },
                            basicInfo = any(),
                        )
                    }
                }
            }
        }
    })

internal fun captureErrorLogs(loggerName: String, block: () -> Unit): List<ILoggingEvent> {
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    val logger = LoggerFactory.getLogger(loggerName) as Logger
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
    }
    return appender.list.filter { it.level == Level.ERROR }
}
