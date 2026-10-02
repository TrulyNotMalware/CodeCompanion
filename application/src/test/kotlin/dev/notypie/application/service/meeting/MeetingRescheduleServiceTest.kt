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
import dev.notypie.impl.command.SlackOutboundStager
import dev.notypie.impl.command.event.MessageType
import dev.notypie.impl.command.event.createSendSlackMessageEvent
import dev.notypie.repository.meeting.MeetingReminderRepository
import dev.notypie.repository.meeting.MeetingRepository
import dev.notypie.repository.meeting.RescheduleResult
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.UUID

class MeetingRescheduleServiceTest :
    BehaviorSpec({
        val clock = createFixedClock(now = LocalDateTime.of(2026, 7, 1, 14, 29, 40))
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
                transactionManager = createH2TransactionManager(),
                clock = clock,
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
                } returns
                    RescheduleResult.Rescheduled(
                        meeting =
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

            `when`("the first attempt loses an optimistic-lock race and the retry wins") {
                val transactionManager = createH2TransactionManager()
                val recordingPublisher = CommitRecordingEventPublisher()
                val localMeetingRepository = mockk<MeetingRepository>()
                val localReminderRepository = mockk<MeetingReminderRepository>(relaxed = true)
                val localService =
                    MeetingRescheduleService(
                        meetingRepository = localMeetingRepository,
                        reminderRepository = localReminderRepository,
                        outboundStager = SlackOutboundStager(slackEventBuilder = mockk(), standupRepository = mockk()),
                        eventPublisher = recordingPublisher,
                        transactionManager = transactionManager,
                        clock = clock,
                    )
                every {
                    localMeetingRepository.rescheduleMeeting(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        newStartAt = newStartAt,
                    )
                } answers {
                    transactionManager.failInsideParticipatingTx(exception = createMeetingVersionConflict())
                } andThen
                    RescheduleResult.Rescheduled(
                        meeting =
                            createMeetingDto(meetingId = meetingId, meetingUid = meetingUid, creator = requesterId),
                    )

                val escaped =
                    runCatching {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            localService.rescheduleMeeting(event = event)
                        }
                    }.exceptionOrNull()

                then("the retry's confirmation is committed and reminders are cleared once") {
                    escaped shouldBe null
                    recordingPublisher.committedEphemeralMarkdowns shouldBe
                        listOf("Meeting rescheduled to 2026-07-01 14:30.")
                    verify(exactly = 1) { localReminderRepository.deleteByMeetingId(meetingId = meetingId) }
                }
            }

            fun serviceCapturing(
                localMeetingRepository: MeetingRepository,
                localReminderRepository: MeetingReminderRepository,
                localStager: OutboundMessageStager,
            ) = MeetingRescheduleService(
                meetingRepository = localMeetingRepository,
                reminderRepository = localReminderRepository,
                outboundStager = localStager,
                eventPublisher = mockk(relaxed = true),
                transactionManager = createH2TransactionManager(),
                clock = clock,
            )

            `when`("the repository reports a no-op (non-host or canceled)") {
                val localMeetingRepository = mockk<MeetingRepository>()
                val localReminderRepository = mockk<MeetingReminderRepository>(relaxed = true)
                val localStager = mockk<OutboundMessageStager>()
                val capturedMessage = slot<OutboundMessage>()
                every {
                    localMeetingRepository.rescheduleMeeting(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        newStartAt = newStartAt,
                    )
                } returns RescheduleResult.NotAuthorized
                every {
                    localStager.stage(message = capture(capturedMessage), basicInfo = any())
                } returns ephemeralEvent

                serviceCapturing(
                    localMeetingRepository = localMeetingRepository,
                    localReminderRepository = localReminderRepository,
                    localStager = localStager,
                ).rescheduleMeeting(event = event)

                then("a friendly non-host-or-canceled ephemeral is published instead of throwing") {
                    val ephemeral = capturedMessage.captured as OutboundMessage.Ephemeral
                    val body = (ephemeral.content as MessageContent.Text).markdown
                    body shouldBe "Meeting was canceled, or you are not the host."
                }

                then("reminders are NOT cleared and no re-notification is published") {
                    verify(exactly = 0) { localReminderRepository.deleteByMeetingId(any()) }
                    verify(exactly = 0) {
                        localStager.stage(
                            message = match { it is OutboundMessage.ChannelMessage },
                            basicInfo = any(),
                        )
                    }
                }
            }

            `when`("the meeting already starts at the requested time (a resubmitted reschedule)") {
                val localMeetingRepository = mockk<MeetingRepository>()
                val localReminderRepository = mockk<MeetingReminderRepository>(relaxed = true)
                val localStager = mockk<OutboundMessageStager>()
                val capturedMessage = slot<OutboundMessage>()
                every {
                    localMeetingRepository.rescheduleMeeting(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        newStartAt = newStartAt,
                    )
                } returns RescheduleResult.AlreadyAtRequestedTime
                every {
                    localStager.stage(message = capture(capturedMessage), basicInfo = any())
                } returns ephemeralEvent

                serviceCapturing(
                    localMeetingRepository = localMeetingRepository,
                    localReminderRepository = localReminderRepository,
                    localStager = localStager,
                ).rescheduleMeeting(event = event)

                then("only a neutral ephemeral goes to the host: no notice and no reminder change") {
                    val ephemeral = capturedMessage.captured as OutboundMessage.Ephemeral
                    ephemeral.recipient shouldBe UserRef(id = requesterId)
                    (ephemeral.content as MessageContent.Text).markdown shouldBe
                        "The meeting is already scheduled for 2026-07-01 14:30. Nothing was changed."
                    verify(exactly = 1) { localStager.stage(message = any(), basicInfo = any()) }
                    verify(exactly = 0) { localReminderRepository.deleteByMeetingId(any()) }
                }
            }

            `when`("the new start is not after the current minute") {
                val localMeetingRepository = mockk<MeetingRepository>()
                val localStager = mockk<OutboundMessageStager>()
                val capturedMessages = mutableListOf<OutboundMessage>()
                every {
                    localStager.stage(message = capture(capturedMessages), basicInfo = any())
                } returns ephemeralEvent
                val localService =
                    serviceCapturing(
                        localMeetingRepository = localMeetingRepository,
                        localReminderRepository = mockk(relaxed = true),
                        localStager = localStager,
                    )

                listOf(LocalDateTime.of(2026, 7, 1, 9, 0), LocalDateTime.of(2026, 7, 1, 14, 29)).forEach { start ->
                    localService.rescheduleMeeting(
                        event =
                            createRescheduleMeetingEvent(
                                meetingUid = meetingUid,
                                requesterId = requesterId,
                                newStartAt = start,
                                responseBasicInfo = basic,
                            ),
                    )
                }

                then("the host is told to pick a future time and the meeting is never touched") {
                    val ephemerals = capturedMessages.map { it as OutboundMessage.Ephemeral }
                    ephemerals.map { (it.content as MessageContent.Text).markdown } shouldBe
                        List(size = 2) { "Pick a future time. The meeting was not rescheduled." }
                    ephemerals.map { it.recipient } shouldBe List(size = 2) { UserRef(id = requesterId) }
                    verify(exactly = 0) {
                        localMeetingRepository.rescheduleMeeting(
                            meetingUid = any(),
                            requesterId = any(),
                            newStartAt = any(),
                        )
                    }
                }
            }

            `when`("the rescheduled meeting's title carries mrkdwn control sequences") {
                val localStager = mockk<OutboundMessageStager>()
                val staged = mutableListOf<OutboundMessage>()
                val localMeetingRepository = mockk<MeetingRepository>()
                every {
                    localMeetingRepository.rescheduleMeeting(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                        newStartAt = newStartAt,
                    )
                } returns
                    RescheduleResult.Rescheduled(
                        meeting =
                            createMeetingDto(
                                meetingId = meetingId,
                                meetingUid = meetingUid,
                                creator = requesterId,
                                title = "<!channel> R&D <https://evil.example|docs>",
                                startAt = newStartAt,
                                participants = listOf(createMeetingParticipantDto(userId = "U_P1")),
                            ),
                    )
                every { localStager.stage(message = capture(staged), basicInfo = any()) } returns ephemeralEvent

                serviceCapturing(
                    localMeetingRepository = localMeetingRepository,
                    localReminderRepository = mockk(relaxed = true),
                    localStager = localStager,
                ).rescheduleMeeting(event = event)

                then("the channel notice escapes the title and keeps its own participant mentions") {
                    val notice = staged.filterIsInstance<OutboundMessage.ChannelMessage>().single()
                    (notice.content as MessageContent.Text).markdown shouldBe
                        "[Notice] <@U_P1> *&lt;!channel&gt; R&amp;D &lt;https://evil.example|docs&gt;* " +
                        "has been rescheduled to 2026-07-01 14:30."
                }
            }

            `when`("the repository throws an unexpected error") {
                val localMeetingRepository = mockk<MeetingRepository>()
                val localReminderRepository = mockk<MeetingReminderRepository>(relaxed = true)
                val localStager = mockk<OutboundMessageStager>()
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

                serviceCapturing(
                    localMeetingRepository = localMeetingRepository,
                    localReminderRepository = localReminderRepository,
                    localStager = localStager,
                ).rescheduleMeeting(event = event)

                then("the listener swallows the failure and surfaces a retry-later ephemeral without retrying") {
                    val ephemeral = capturedMessage.captured as OutboundMessage.Ephemeral
                    val body = (ephemeral.content as MessageContent.Text).markdown
                    body shouldBe "Failed to reschedule the meeting. Please try again later."
                    verify(exactly = 1) {
                        localMeetingRepository.rescheduleMeeting(
                            meetingUid = any(),
                            requesterId = any(),
                            newStartAt = any(),
                        )
                    }
                    verify(exactly = 0) { localReminderRepository.deleteByMeetingId(any()) }
                }
            }
        }
    })
