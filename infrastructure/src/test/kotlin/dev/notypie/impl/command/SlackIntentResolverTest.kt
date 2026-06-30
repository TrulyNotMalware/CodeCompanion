package dev.notypie.impl.command

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createOpenViewEvent
import dev.notypie.domain.command.createSendSlackMessageEvent
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CancelMeetingEvent
import dev.notypie.domain.command.entity.event.GetMeetingListEvent
import dev.notypie.domain.command.entity.event.MessageType
import dev.notypie.domain.command.entity.event.OpenViewEvent
import dev.notypie.domain.command.entity.event.PostEventPayloadContents
import dev.notypie.domain.command.entity.event.RecordStandupAnswerEvent
import dev.notypie.domain.command.entity.event.SendSlackMessageEvent
import dev.notypie.domain.command.entity.event.StatusReportRequestEvent
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendanceEvent
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.meet.entity.RejectReason
import dev.notypie.domain.standup.createRoutineDto
import dev.notypie.domain.standup.createStandupSessionDto
import dev.notypie.repository.standup.StandupRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class SlackIntentResolverTest :
    BehaviorSpec({
        val slackEventBuilder = mockk<SlackApiEventConstructor>()
        // Repository is required by the resolver but only consulted on the OpenStandupModal
        // branch; tests that don't exercise that branch can leave it unstubbed.
        val resolver =
            SlackIntentResolver(
                slackEventBuilder = slackEventBuilder,
                standupRepository = mockk(),
            )

        val basicInfo = createCommandBasicInfo()
        val commandDetailType = CommandDetailType.SIMPLE_TEXT
        val stubEvent =
            createSendSlackMessageEvent(
                commandDetailType = commandDetailType,
                idempotencyKey = basicInfo.idempotencyKey,
            )

        given("MeetingListRequest intent") {
            val startDate = LocalDateTime.now()
            val endDate = startDate.plusWeeks(1)
            val intent =
                CommandIntent.MeetingListRequest(
                    publisherId = basicInfo.publisherId,
                    startDate = startDate,
                    endDate = endDate,
                )

            `when`("resolveAll is called") {
                val events =
                    resolver.resolveAll(
                        intents = listOf(intent),
                        basicInfo = basicInfo,
                    )

                then("produces a GetMeetingListEvent with correct payload and no SlackEventBuilder interaction") {
                    events shouldHaveSize 1
                    val event = events.first()
                    event.shouldBeInstanceOf<GetMeetingListEvent>()
                    event.payload.publisherId shouldBe basicInfo.publisherId
                    event.payload.startDate shouldBe startDate
                    event.payload.endDate shouldBe endDate
                    event.payload.responseBasicInfo shouldBe basicInfo
                    event.idempotencyKey shouldBe basicInfo.idempotencyKey
                    event.type shouldBe CommandDetailType.GET_MEETING_LIST
                }
            }
        }

        given("OpenDeclineReasonModal intent") {
            val meetingKey = UUID.randomUUID()
            val triggerId = "trigger_xyz_123"
            val noticeChannel = "C_NOTICE"
            val noticeMessageTs = "1700000000.000200"
            val intent =
                CommandIntent.OpenDeclineReasonModal(
                    triggerId = triggerId,
                    meetingIdempotencyKey = meetingKey,
                    participantUserId = "U_PARTICIPANT_X",
                    meetingTitle = "Weekly sync",
                    noticeChannel = noticeChannel,
                    noticeMessageTs = noticeMessageTs,
                )
            val stubOpenViewEvent =
                createOpenViewEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    appId = basicInfo.appId,
                    publisherId = basicInfo.publisherId,
                    channel = basicInfo.channel,
                    triggerId = triggerId,
                    meetingIdempotencyKey = meetingKey,
                    participantUserId = "U_PARTICIPANT_X",
                )

            `when`("resolveAll is called") {
                every {
                    slackEventBuilder.openDeclineReasonModalRequest(
                        commandBasicInfo = basicInfo,
                        commandDetailType = CommandDetailType.DECLINE_REASON_MODAL,
                        triggerId = triggerId,
                        meetingIdempotencyKey = meetingKey,
                        participantUserId = "U_PARTICIPANT_X",
                        meetingTitle = "Weekly sync",
                        noticeChannel = noticeChannel,
                        noticeMessageTs = noticeMessageTs,
                    )
                } returns stubOpenViewEvent

                val events =
                    resolver.resolveAll(
                        intents = listOf(intent),
                        basicInfo = basicInfo,
                    )

                then("produces an OpenViewEvent by delegating to SlackApiEventConstructor") {
                    events shouldHaveSize 1
                    events.first().shouldBeInstanceOf<OpenViewEvent>()
                    verify(exactly = 1) {
                        slackEventBuilder.openDeclineReasonModalRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.DECLINE_REASON_MODAL,
                            triggerId = triggerId,
                            meetingIdempotencyKey = meetingKey,
                            participantUserId = "U_PARTICIPANT_X",
                            meetingTitle = "Weekly sync",
                            noticeChannel = noticeChannel,
                            noticeMessageTs = noticeMessageTs,
                        )
                    }
                }
            }
        }

        given("UpdateNoticeMessage intent") {
            val channel = "C_UPDATE"
            val messageTs = "1700000000.000300"
            val markdown = "You declined the meeting — *Reason:* Other"
            val intent =
                CommandIntent.UpdateNoticeMessage(
                    channel = channel,
                    messageTs = messageTs,
                    markdownText = markdown,
                )
            val stubUpdateEvent =
                createSendSlackMessageEvent(
                    commandDetailType = CommandDetailType.DECLINE_REASON_MODAL,
                    idempotencyKey = basicInfo.idempotencyKey,
                    appId = basicInfo.appId,
                    publisherId = basicInfo.publisherId,
                    channel = channel,
                    messageType = MessageType.UPDATE_MESSAGE,
                )

            `when`("resolveAll is called") {
                every {
                    slackEventBuilder.updateNoticeMessageRequest(
                        commandBasicInfo = basicInfo,
                        commandDetailType = CommandDetailType.DECLINE_REASON_MODAL,
                        channel = channel,
                        messageTs = messageTs,
                        markdownText = markdown,
                    )
                } returns stubUpdateEvent

                val events =
                    resolver.resolveAll(
                        intents = listOf(intent),
                        basicInfo = basicInfo,
                    )

                then("routes through SlackApiEventConstructor.updateNoticeMessageRequest") {
                    events shouldHaveSize 1
                    val sendEvent = events.first()
                    sendEvent.shouldBeInstanceOf<SendSlackMessageEvent>()
                    val payload = sendEvent.payload
                    payload.shouldBeInstanceOf<PostEventPayloadContents>()
                    payload.messageType shouldBe MessageType.UPDATE_MESSAGE
                    verify(exactly = 1) {
                        slackEventBuilder.updateNoticeMessageRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.DECLINE_REASON_MODAL,
                            channel = channel,
                            messageTs = messageTs,
                            markdownText = markdown,
                        )
                    }
                }
            }
        }

        given("MeetingAttendanceUpdate intent") {
            `when`("participant declines (isAttending = false)") {
                val meetingKey = UUID.randomUUID()
                val intent =
                    CommandIntent.MeetingAttendanceUpdate(
                        meetingIdempotencyKey = meetingKey,
                        participantUserId = "U_PARTICIPANT",
                        isAttending = false,
                        absentReason = RejectReason.OTHER,
                    )

                val events =
                    resolver.resolveAll(
                        intents = listOf(intent),
                        basicInfo = basicInfo,
                    )

                then("produces UpdateMeetingAttendanceEvent (isAttending=false) without touching slackEventBuilder") {
                    events shouldHaveSize 1
                    val event = events.first()
                    event.shouldBeInstanceOf<UpdateMeetingAttendanceEvent>()
                    event.idempotencyKey shouldBe basicInfo.idempotencyKey
                    event.type shouldBe CommandDetailType.MEETING_APPROVAL_NOTICE_FORM
                    event.payload.meetingIdempotencyKey shouldBe meetingKey
                    event.payload.participantUserId shouldBe "U_PARTICIPANT"
                    event.payload.isAttending shouldBe false
                    event.payload.absentReason shouldBe RejectReason.OTHER
                }
            }

            `when`("participant accepts (isAttending = true)") {
                val meetingKey = UUID.randomUUID()
                val intent =
                    CommandIntent.MeetingAttendanceUpdate(
                        meetingIdempotencyKey = meetingKey,
                        participantUserId = "U_ACCEPTOR",
                        isAttending = true,
                        absentReason = RejectReason.ATTENDING,
                    )

                val events =
                    resolver.resolveAll(
                        intents = listOf(intent),
                        basicInfo = basicInfo,
                    )

                then("produces UpdateMeetingAttendanceEvent (isAttending=true) without touching slackEventBuilder") {
                    events shouldHaveSize 1
                    val event = events.first()
                    event.shouldBeInstanceOf<UpdateMeetingAttendanceEvent>()
                    event.idempotencyKey shouldBe basicInfo.idempotencyKey
                    event.type shouldBe CommandDetailType.MEETING_APPROVAL_NOTICE_FORM
                    event.payload.meetingIdempotencyKey shouldBe meetingKey
                    event.payload.participantUserId shouldBe "U_ACCEPTOR"
                    event.payload.isAttending shouldBe true
                    event.payload.absentReason shouldBe RejectReason.ATTENDING
                }
            }
        }

        given("StatusReport intent") {
            `when`("@bot status fires the parameterless StatusReport intent") {
                val intent = CommandIntent.StatusReport

                val events =
                    resolver.resolveAll(
                        intents = listOf(intent),
                        basicInfo = basicInfo,
                    )

                then("produces a StatusReportRequestEvent without touching slackEventBuilder") {
                    events shouldHaveSize 1
                    val event = events.first()
                    event.shouldBeInstanceOf<StatusReportRequestEvent>()
                    event.idempotencyKey shouldBe basicInfo.idempotencyKey
                    event.type shouldBe CommandDetailType.STATUS_REPORT
                    event.payload.responseBasicInfo shouldBe basicInfo
                }
            }
        }

        given("CancelMeeting intent") {
            `when`("a host requests cancellation") {
                val meetingUid = UUID.randomUUID()
                val requesterId = "U_HOST_CANCEL"
                val intent =
                    CommandIntent.CancelMeeting(
                        meetingUid = meetingUid,
                        requesterId = requesterId,
                    )

                val events =
                    resolver.resolveAll(
                        intents = listOf(intent),
                        basicInfo = basicInfo,
                    )

                then("produces a CancelMeetingEvent without touching slackEventBuilder") {
                    events shouldHaveSize 1
                    val event = events.first()
                    event.shouldBeInstanceOf<CancelMeetingEvent>()
                    event.idempotencyKey shouldBe basicInfo.idempotencyKey
                    event.type shouldBe CommandDetailType.CANCEL_MEETING
                    event.payload.meetingUid shouldBe meetingUid
                    event.payload.requesterId shouldBe requesterId
                    event.payload.responseBasicInfo shouldBe basicInfo
                }
            }
        }

        given("ReplaceMessage intent") {
            val intent =
                CommandIntent.ReplaceMessage(
                    markdownText = "replacement",
                    responseUrl = "https://hooks.slack.com/foo",
                )

            `when`("resolveAll is called") {
                every {
                    slackEventBuilder.replaceOriginalText(
                        markdownText = any(),
                        responseUrl = any(),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                    )
                } returns stubEvent

                resolver.resolveAll(
                    intents = listOf(intent),
                    basicInfo = basicInfo,
                )

                then("calls replaceOriginalText with intent default commandDetailType (REPLACE_TEXT)") {
                    verify(exactly = 1) {
                        slackEventBuilder.replaceOriginalText(
                            markdownText = "replacement",
                            responseUrl = "https://hooks.slack.com/foo",
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.REPLACE_TEXT,
                        )
                    }
                }
            }
        }

        given("OpenStandupModal intent") {
            val standupRepository = mockk<StandupRepository>()
            val standupResolver =
                SlackIntentResolver(
                    slackEventBuilder = slackEventBuilder,
                    standupRepository = standupRepository,
                )
            val routineUid = UUID.randomUUID()
            val sessionUid = UUID.randomUUID()
            val sessionDate = LocalDate.of(2026, 5, 4)
            val intent =
                CommandIntent.OpenStandupModal(
                    triggerId = "trigger-standup",
                    sessionUid = sessionUid,
                    routineUid = routineUid,
                    requesterId = "U_STANDUP",
                    noticeChannel = "D_NOTICE",
                    noticeMessageTs = "1700000000.000600",
                )
            val stubOpenViewEvent =
                createOpenViewEvent(
                    idempotencyKey = basicInfo.idempotencyKey,
                    commandDetailType = CommandDetailType.STANDUP_FILL,
                    triggerId = "trigger-standup",
                    viewJson = "{}",
                )

            `when`("resolveAll is called") {
                every { standupRepository.getRoutine(routineUid = routineUid) } returns
                    createRoutineDto(
                        routineUid = routineUid,
                        name = "Daily Standup",
                        questions = listOf("Yesterday?", "Today?"),
                    )
                every { standupRepository.findSession(sessionUid = sessionUid) } returns
                    createStandupSessionDto(
                        sessionUid = sessionUid,
                        routineUid = routineUid,
                        sessionDate = sessionDate,
                    )
                every {
                    slackEventBuilder.openStandupModalRequest(
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        triggerId = any(),
                        sessionUid = any(),
                        routineName = any(),
                        sessionDate = any(),
                        questions = any(),
                        userId = any(),
                        noticeChannel = any(),
                        noticeMessageTs = any(),
                    )
                } returns stubOpenViewEvent

                val events = standupResolver.resolveAll(intents = listOf(intent), basicInfo = basicInfo)

                then("routine/session data is loaded and delegated to the modal event builder") {
                    events.single().shouldBeInstanceOf<OpenViewEvent>()
                    verify(exactly = 1) {
                        slackEventBuilder.openStandupModalRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.STANDUP_FILL,
                            triggerId = "trigger-standup",
                            sessionUid = sessionUid,
                            routineName = "Daily Standup",
                            sessionDate = sessionDate,
                            questions = listOf("Yesterday?", "Today?"),
                            userId = "U_STANDUP",
                            noticeChannel = "D_NOTICE",
                            noticeMessageTs = "1700000000.000600",
                        )
                    }
                }
            }
        }

        given("RecordStandupAnswer intent") {
            val sessionUid = UUID.randomUUID()
            val intent =
                CommandIntent.RecordStandupAnswer(
                    sessionUid = sessionUid,
                    userId = "U_STANDUP",
                    responses = listOf("Finished #12", "Working on #13"),
                )

            `when`("resolveAll is called") {
                val event =
                    resolver
                        .resolveAll(intents = listOf(intent), basicInfo = basicInfo)
                        .single()

                then("it produces an internal RecordStandupAnswerEvent") {
                    val record = event.shouldBeInstanceOf<RecordStandupAnswerEvent>()
                    record.payload.sessionUid shouldBe sessionUid
                    record.payload.userId shouldBe "U_STANDUP"
                    record.payload.responses shouldBe listOf("Finished #12", "Working on #13")
                    record.type shouldBe CommandDetailType.STANDUP_ANSWER_SUBMIT
                }
            }
        }

        given("Nothing intent") {
            `when`("resolveAll is called with only Nothing intents") {
                val events =
                    resolver.resolveAll(
                        intents = listOf(CommandIntent.Nothing),
                        basicInfo = basicInfo,
                    )

                then("returns empty list and does not invoke any builder method") {
                    events.shouldHaveSize(0)
                }
            }
        }

        given("mixed intents with heterogeneous commandDetailType (regression for intent routing collapse)") {
            `when`("resolveAll is called with ReplaceMessage + UpdateNoticeMessage in one batch") {
                val replaceSlot = slot<CommandDetailType>()
                val updateNoticeSlot = slot<CommandDetailType>()
                every {
                    slackEventBuilder.replaceOriginalText(
                        markdownText = any(),
                        responseUrl = any(),
                        commandBasicInfo = any(),
                        commandDetailType = capture(replaceSlot),
                    )
                } returns stubEvent
                every {
                    slackEventBuilder.updateNoticeMessageRequest(
                        commandBasicInfo = any(),
                        commandDetailType = capture(updateNoticeSlot),
                        channel = any(),
                        messageTs = any(),
                        markdownText = any(),
                    )
                } returns stubEvent

                val intents =
                    listOf(
                        CommandIntent.ReplaceMessage(
                            markdownText = "done",
                            responseUrl = "https://hooks.slack.com/x",
                        ),
                        CommandIntent.UpdateNoticeMessage(
                            channel = "C_NOTICE",
                            messageTs = "1700000000.000200",
                            markdownText = "declined",
                        ),
                    )

                resolver.resolveAll(
                    intents = intents,
                    basicInfo = basicInfo,
                )

                then("each intent's own commandDetailType is preserved, not collapsed to a single command-level type") {
                    replaceSlot.captured shouldBe CommandDetailType.REPLACE_TEXT
                    updateNoticeSlot.captured shouldBe CommandDetailType.DECLINE_REASON_MODAL
                }
            }
        }

        given("mixed intents") {
            `when`("resolveAll is called with ReplaceMessage + Nothing") {
                every {
                    slackEventBuilder.replaceOriginalText(
                        markdownText = any(),
                        responseUrl = any(),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                    )
                } returns stubEvent

                val events =
                    resolver.resolveAll(
                        intents =
                            listOf(
                                CommandIntent.ReplaceMessage(
                                    markdownText = "done",
                                    responseUrl = "https://hooks.slack.com/x",
                                ),
                                CommandIntent.Nothing,
                            ),
                        basicInfo = basicInfo,
                    )

                then("Nothing is filtered out while ReplaceMessage is resolved") {
                    events shouldHaveSize 1
                }
            }
        }
    })
