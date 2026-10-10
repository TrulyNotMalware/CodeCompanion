package dev.notypie.impl.command

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.AddParticipantEvent
import dev.notypie.domain.command.entity.event.AgentUsageReportRequestEvent
import dev.notypie.domain.command.entity.event.CalendarConnectionAction
import dev.notypie.domain.command.entity.event.CalendarConnectionRequestEvent
import dev.notypie.domain.command.entity.event.CancelMeetingEvent
import dev.notypie.domain.command.entity.event.CveSubscriptionAction
import dev.notypie.domain.command.entity.event.CveSubscriptionRequestEvent
import dev.notypie.domain.command.entity.event.GetMeetingListEvent
import dev.notypie.domain.command.entity.event.RecordStandupAnswerEvent
import dev.notypie.domain.command.entity.event.RescheduleMeetingEvent
import dev.notypie.domain.command.entity.event.StandupOpsAction
import dev.notypie.domain.command.entity.event.StandupOpsRequestEvent
import dev.notypie.domain.command.entity.event.StatusReportRequestEvent
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendanceEvent
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.ResponseReplaceHandle
import dev.notypie.domain.meet.entity.RejectReason
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDateTime
import java.util.UUID

class SlackIntentResolverTest :
    BehaviorSpec({
        val resolver = SlackIntentResolver()

        val basicInfo = createCommandBasicInfo()

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

                then("produces a GetMeetingListEvent with correct payload") {
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

                then("produces UpdateMeetingAttendanceEvent (isAttending=false)") {
                    events shouldHaveSize 1
                    val event = events.first()
                    event.shouldBeInstanceOf<UpdateMeetingAttendanceEvent>()
                    event.idempotencyKey shouldBe basicInfo.idempotencyKey
                    event.type shouldBe CommandDetailType.MEETING_APPROVAL_REQUEST
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

                then("produces UpdateMeetingAttendanceEvent (isAttending=true)") {
                    events shouldHaveSize 1
                    val event = events.first()
                    event.shouldBeInstanceOf<UpdateMeetingAttendanceEvent>()
                    event.idempotencyKey shouldBe basicInfo.idempotencyKey
                    event.type shouldBe CommandDetailType.MEETING_APPROVAL_REQUEST
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

                then("produces a StatusReportRequestEvent") {
                    events shouldHaveSize 1
                    val event = events.first()
                    event.shouldBeInstanceOf<StatusReportRequestEvent>()
                    event.idempotencyKey shouldBe basicInfo.idempotencyKey
                    event.type shouldBe CommandDetailType.STATUS_REPORT
                    event.payload.responseBasicInfo shouldBe basicInfo
                }
            }
        }

        given("AgentUsageReport intent") {
            `when`("@bot usage 30 fires the AgentUsageReport intent") {
                val events =
                    resolver.resolveAll(
                        intents = listOf(CommandIntent.AgentUsageReport(days = 30)),
                        basicInfo = basicInfo,
                    )

                then("produces an internal AgentUsageReportRequestEvent carrying the window") {
                    events shouldHaveSize 1
                    val event = events.first()
                    event.shouldBeInstanceOf<AgentUsageReportRequestEvent>()
                    event.idempotencyKey shouldBe basicInfo.idempotencyKey
                    event.type shouldBe CommandDetailType.AGENT_USAGE_REPORT
                    event.isInternal shouldBe true
                    event.payload.days shouldBe 30
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
                        listHandle = ResponseReplaceHandle(raw = "https://hooks.slack.com/actions/T1/1/list"),
                    )

                val events =
                    resolver.resolveAll(
                        intents = listOf(intent),
                        basicInfo = basicInfo,
                    )

                then("produces a CancelMeetingEvent") {
                    events shouldHaveSize 1
                    val event = events.first()
                    event.shouldBeInstanceOf<CancelMeetingEvent>()
                    event.idempotencyKey shouldBe basicInfo.idempotencyKey
                    event.type shouldBe CommandDetailType.CANCEL_MEETING
                    event.payload.meetingUid shouldBe meetingUid
                    event.payload.requesterId shouldBe requesterId
                    event.payload.listHandle shouldBe intent.listHandle
                    event.payload.responseBasicInfo shouldBe basicInfo
                }
            }
        }

        given("RescheduleMeeting and AddParticipant intents submitted from a list row's modal") {
            val listHandle = ResponseReplaceHandle(raw = "https://hooks.slack.com/actions/T1/2/list")
            val meetingUid = UUID.randomUUID()

            `when`("both are resolved") {
                val events =
                    resolver.resolveAll(
                        intents =
                            listOf(
                                CommandIntent.RescheduleMeeting(
                                    meetingUid = meetingUid,
                                    requesterId = "U_HOST",
                                    newStartAt = LocalDateTime.of(2099, 1, 1, 10, 0),
                                    listHandle = listHandle,
                                ),
                                CommandIntent.AddParticipant(
                                    meetingUid = meetingUid,
                                    requesterId = "U_HOST",
                                    participantUserIds = listOf("U_A"),
                                    listHandle = listHandle,
                                ),
                            ),
                        basicInfo = basicInfo,
                    )

                then("each event payload carries the list handle to the write service") {
                    events
                        .filterIsInstance<RescheduleMeetingEvent>()
                        .single()
                        .payload.listHandle shouldBe listHandle
                    events
                        .filterIsInstance<AddParticipantEvent>()
                        .single()
                        .payload.listHandle shouldBe listHandle
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
                    notice = MessageRef(conversation = ConversationTarget(id = "D_NOTICE"), messageId = "1.2"),
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
                    record.payload.notice shouldBe
                        MessageRef(conversation = ConversationTarget(id = "D_NOTICE"), messageId = "1.2")
                    record.type shouldBe CommandDetailType.STANDUP_ANSWER_SUBMIT
                }
            }
        }

        given("CveSubscribe intent") {
            val intent =
                CommandIntent.CveSubscribe(
                    userId = "U_SUBSCRIBER",
                    topicKeys = listOf("kotlin", "spring"),
                )

            `when`("resolveAll is called") {
                val event =
                    resolver
                        .resolveAll(intents = listOf(intent), basicInfo = basicInfo)
                        .single()

                then("it produces a SUBSCRIBE CveSubscriptionRequestEvent") {
                    val request = event.shouldBeInstanceOf<CveSubscriptionRequestEvent>()
                    request.payload.action shouldBe CveSubscriptionAction.SUBSCRIBE
                    request.payload.userId shouldBe "U_SUBSCRIBER"
                    request.payload.topicKeys shouldBe listOf("kotlin", "spring")
                    request.payload.responseBasicInfo shouldBe basicInfo
                    request.type shouldBe CommandDetailType.CVE_SUBSCRIBE_SUBMIT
                }
            }
        }

        given("CveUnsubscribe intent") {
            val intent =
                CommandIntent.CveUnsubscribe(
                    userId = "U_SUBSCRIBER",
                    topicKeys = listOf("cve-java"),
                )

            `when`("resolveAll is called") {
                val event =
                    resolver
                        .resolveAll(intents = listOf(intent), basicInfo = basicInfo)
                        .single()

                then("it produces an UNSUBSCRIBE CveSubscriptionRequestEvent") {
                    val request = event.shouldBeInstanceOf<CveSubscriptionRequestEvent>()
                    request.payload.action shouldBe CveSubscriptionAction.UNSUBSCRIBE
                    request.payload.topicKeys shouldBe listOf("cve-java")
                    request.type shouldBe CommandDetailType.CVE_UNSUBSCRIBE_SUBMIT
                }
            }
        }

        given("CveListSubscriptions intent") {
            val intent = CommandIntent.CveListSubscriptions(userId = "U_SUBSCRIBER")

            `when`("resolveAll is called") {
                val event =
                    resolver
                        .resolveAll(intents = listOf(intent), basicInfo = basicInfo)
                        .single()

                then("it produces a LIST CveSubscriptionRequestEvent with no topic keys") {
                    val request = event.shouldBeInstanceOf<CveSubscriptionRequestEvent>()
                    request.payload.action shouldBe CveSubscriptionAction.LIST
                    request.payload.userId shouldBe "U_SUBSCRIBER"
                    request.payload.topicKeys shouldBe emptyList()
                    request.type shouldBe CommandDetailType.CVE_SUBSCRIPTIONS_LIST
                }
            }
        }

        given("ListStandupRoutines intent") {
            `when`("resolveAll is called") {
                val event =
                    resolver
                        .resolveAll(intents = listOf(CommandIntent.ListStandupRoutines), basicInfo = basicInfo)
                        .single()

                then("it produces a LIST StandupOpsRequestEvent with no routine name") {
                    val request = event.shouldBeInstanceOf<StandupOpsRequestEvent>()
                    request.payload.action shouldBe StandupOpsAction.LIST
                    request.payload.routineName shouldBe null
                    request.payload.responseBasicInfo shouldBe basicInfo
                    request.idempotencyKey shouldBe basicInfo.idempotencyKey
                    request.type shouldBe CommandDetailType.STANDUP_ROUTINE_LIST
                }
            }
        }

        given("StopStandupRoutine intent") {
            val intent = CommandIntent.StopStandupRoutine(routineName = "daily sync")

            `when`("resolveAll is called") {
                val event =
                    resolver
                        .resolveAll(intents = listOf(intent), basicInfo = basicInfo)
                        .single()

                then("it produces a STOP StandupOpsRequestEvent carrying the routine name") {
                    val request = event.shouldBeInstanceOf<StandupOpsRequestEvent>()
                    request.payload.action shouldBe StandupOpsAction.STOP
                    request.payload.routineName shouldBe "daily sync"
                    request.payload.responseBasicInfo shouldBe basicInfo
                    request.idempotencyKey shouldBe basicInfo.idempotencyKey
                    request.type shouldBe CommandDetailType.STANDUP_ROUTINE_STOP
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

                then("returns empty list") {
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
                            listHandle = null,
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

        given("calendar connection intents") {
            `when`("CalendarConnect, CalendarDisconnect and CalendarStatus are resolved") {
                val events =
                    resolver.resolveAll(
                        intents =
                            listOf(
                                CommandIntent.CalendarConnect(userId = "U_CAL"),
                                CommandIntent.CalendarDisconnect(userId = "U_CAL"),
                                CommandIntent.CalendarStatus(userId = "U_CAL"),
                            ),
                        basicInfo = basicInfo,
                    )

                then("each becomes a CalendarConnectionRequestEvent carrying its action and the requester") {
                    events shouldHaveSize 3
                    events.map { it.shouldBeInstanceOf<CalendarConnectionRequestEvent>().payload.action } shouldBe
                        listOf(
                            CalendarConnectionAction.CONNECT,
                            CalendarConnectionAction.DISCONNECT,
                            CalendarConnectionAction.STATUS,
                        )
                    events.forEach { event ->
                        event.shouldBeInstanceOf<CalendarConnectionRequestEvent>()
                        event.payload.userId shouldBe "U_CAL"
                        event.payload.responseBasicInfo shouldBe basicInfo
                        event.idempotencyKey shouldBe basicInfo.idempotencyKey
                        event.type shouldBe CommandDetailType.CALENDAR_CONNECTION
                    }
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
