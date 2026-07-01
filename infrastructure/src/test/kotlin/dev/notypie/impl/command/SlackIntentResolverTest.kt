package dev.notypie.impl.command

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createOpenViewEvent
import dev.notypie.domain.command.createSendSlackMessageEvent
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CancelMeetingEvent
import dev.notypie.domain.command.entity.event.GetMeetingListEvent
import dev.notypie.domain.command.entity.event.OpenViewEvent
import dev.notypie.domain.command.entity.event.RecordStandupAnswerEvent
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
            `when`("resolveAll is called with CancelMeeting + StatusReport in one batch") {
                val intents =
                    listOf(
                        CommandIntent.CancelMeeting(
                            meetingUid = UUID.randomUUID(),
                            requesterId = "U_HOST",
                        ),
                        CommandIntent.StatusReport,
                    )

                val events =
                    resolver.resolveAll(
                        intents = intents,
                        basicInfo = basicInfo,
                    )

                then("each intent's own commandDetailType is preserved, not collapsed to a single command-level type") {
                    events shouldHaveSize 2
                    events[0].type shouldBe CommandDetailType.CANCEL_MEETING
                    events[1].type shouldBe CommandDetailType.STATUS_REPORT
                }
            }
        }

        given("mixed intents") {
            `when`("resolveAll is called with StatusReport + Nothing") {
                val events =
                    resolver.resolveAll(
                        intents =
                            listOf(
                                CommandIntent.StatusReport,
                                CommandIntent.Nothing,
                            ),
                        basicInfo = basicInfo,
                    )

                then("Nothing is filtered out while StatusReport is resolved") {
                    events shouldHaveSize 1
                }
            }
        }
    })
