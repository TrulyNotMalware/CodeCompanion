package dev.notypie.impl.command

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createSendSlackMessageEvent
import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectBoxDetails
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.event.GetMeetingListEvent
import dev.notypie.domain.command.entity.event.UpdateMeetingAttendanceEvent
import dev.notypie.domain.command.intent.CommandIntent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDateTime
import java.util.UUID

class SlackIntentResolverTest :
    BehaviorSpec({
        val slackEventBuilder = mockk<SlackApiEventConstructor>()
        val resolver = SlackIntentResolver(slackEventBuilder = slackEventBuilder)

        val basicInfo = createCommandBasicInfo()
        val commandType = CommandType.SIMPLE
        val commandDetailType = CommandDetailType.SIMPLE_TEXT
        val stubEvent =
            createSendSlackMessageEvent(
                commandDetailType = commandDetailType,
                idempotencyKey = basicInfo.idempotencyKey,
            )

        given("TextResponse intent") {
            val intent = CommandIntent.TextResponse(headLine = "hi", message = "hello world")

            `when`("resolveAll is called") {
                every {
                    slackEventBuilder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        simpleString = any(),
                        commandType = any(),
                    )
                } returns stubEvent

                val events =
                    resolver.resolveAll(
                        intents = listOf(intent),
                        basicInfo = basicInfo,
                        commandType = commandType,
                    )

                then("produces one event via simpleTextRequest with intent fields") {
                    events shouldHaveSize 1
                    verify(exactly = 1) {
                        slackEventBuilder.simpleTextRequest(
                            commandDetailType = commandDetailType,
                            headLineText = "hi",
                            commandBasicInfo = basicInfo,
                            simpleString = "hello world",
                            commandType = commandType,
                        )
                    }
                }
            }
        }

        given("EphemeralResponse intent") {
            val intent = CommandIntent.EphemeralResponse(message = "secret", targetUserId = "U_TARGET")

            `when`("resolveAll is called") {
                every {
                    slackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = any(),
                        commandBasicInfo = any(),
                        commandType = any(),
                        commandDetailType = any(),
                        targetUserId = any(),
                    )
                } returns stubEvent

                resolver.resolveAll(
                    intents = listOf(intent),
                    basicInfo = basicInfo,
                    commandType = commandType,
                )

                then("calls simpleEphemeralTextRequest with targetUserId") {
                    verify(exactly = 1) {
                        slackEventBuilder.simpleEphemeralTextRequest(
                            textMessage = "secret",
                            commandBasicInfo = basicInfo,
                            commandType = commandType,
                            commandDetailType = commandDetailType,
                            targetUserId = "U_TARGET",
                        )
                    }
                }
            }
        }

        given("ErrorDetail intent") {
            val intent =
                CommandIntent.ErrorDetail(
                    errorClassName = "TestException",
                    errorMessage = "something broke",
                    details = "stack trace",
                )

            `when`("resolveAll is called") {
                every {
                    slackEventBuilder.detailErrorTextRequest(
                        commandDetailType = any(),
                        errorClassName = any(),
                        errorMessage = any(),
                        details = any(),
                        commandType = any(),
                        commandBasicInfo = any(),
                    )
                } returns stubEvent

                resolver.resolveAll(
                    intents = listOf(intent),
                    basicInfo = basicInfo,
                    commandType = commandType,
                )

                then("calls detailErrorTextRequest with intent default commandDetailType (ERROR_RESPONSE)") {
                    verify(exactly = 1) {
                        slackEventBuilder.detailErrorTextRequest(
                            commandDetailType = CommandDetailType.ERROR_RESPONSE,
                            errorClassName = "TestException",
                            errorMessage = "something broke",
                            details = "stack trace",
                            commandType = commandType,
                            commandBasicInfo = basicInfo,
                        )
                    }
                }
            }
        }

        given("TimeSchedule intent") {
            val scheduleInfo =
                TimeScheduleInfo(
                    scheduleName = "standup",
                    startTime = LocalDateTime.now(),
                    endTime = LocalDateTime.now().plusHours(1),
                )
            val intent = CommandIntent.TimeSchedule(headLine = "daily", timeScheduleInfo = scheduleInfo)

            `when`("resolveAll is called") {
                every {
                    slackEventBuilder.simpleTimeScheduleRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        timeScheduleInfo = any(),
                        commandType = any(),
                    )
                } returns stubEvent

                resolver.resolveAll(
                    intents = listOf(intent),
                    basicInfo = basicInfo,
                    commandType = commandType,
                )

                then("calls simpleTimeScheduleRequest") {
                    verify(exactly = 1) {
                        slackEventBuilder.simpleTimeScheduleRequest(
                            commandDetailType = commandDetailType,
                            headLineText = "daily",
                            commandBasicInfo = basicInfo,
                            timeScheduleInfo = scheduleInfo,
                            commandType = commandType,
                        )
                    }
                }
            }
        }

        given("ApplyReject intent") {
            val approvalContents =
                ApprovalContents(
                    reason = "test",
                    publisherId = basicInfo.publisherId,
                    idempotencyKey = basicInfo.idempotencyKey,
                    commandDetailType = commandDetailType,
                )
            val intent =
                CommandIntent.ApplyReject(
                    approvalContents = approvalContents,
                    targetUserId = "U_TARGET",
                )

            `when`("resolveAll is called") {
                every {
                    slackEventBuilder.simpleApplyRejectRequest(
                        commandDetailType = any(),
                        commandBasicInfo = any(),
                        approvalContents = any(),
                        commandType = any(),
                        targetUserId = any(),
                    )
                } returns stubEvent

                resolver.resolveAll(
                    intents = listOf(intent),
                    basicInfo = basicInfo,
                    commandType = commandType,
                )

                then("calls simpleApplyRejectRequest with commandDetailType derived from approvalContents") {
                    verify(exactly = 1) {
                        slackEventBuilder.simpleApplyRejectRequest(
                            commandDetailType = approvalContents.commandDetailType,
                            commandBasicInfo = basicInfo,
                            approvalContents = approvalContents,
                            commandType = commandType,
                            targetUserId = "U_TARGET",
                        )
                    }
                }
            }
        }

        given("ApprovalForm intent") {
            val selectionFields =
                listOf(
                    SelectionContents(
                        title = "Purpose",
                        explanation = "Select",
                        placeholderText = "pick one",
                        contents =
                            listOf(
                                SelectBoxDetails(name = "A", value = "a"),
                            ),
                    ),
                )
            val reasonInput = TextInputContents(title = "Reason", placeholderText = "why")
            val intent =
                CommandIntent.ApprovalForm(
                    headLine = "Approve",
                    selectionFields = selectionFields,
                    reasonInput = reasonInput,
                )

            `when`("resolveAll is called") {
                every {
                    slackEventBuilder.simpleApprovalFormRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        selectionFields = any(),
                        commandType = any(),
                        reasonInput = any(),
                        approvalContents = any(),
                    )
                } returns stubEvent

                resolver.resolveAll(
                    intents = listOf(intent),
                    basicInfo = basicInfo,
                    commandType = commandType,
                )

                then("calls simpleApprovalFormRequest with intent default commandDetailType (APPROVAL_FORM)") {
                    verify(exactly = 1) {
                        slackEventBuilder.simpleApprovalFormRequest(
                            commandDetailType = CommandDetailType.APPROVAL_FORM,
                            headLineText = "Approve",
                            commandBasicInfo = basicInfo,
                            selectionFields = selectionFields,
                            commandType = commandType,
                            reasonInput = reasonInput,
                            approvalContents = null,
                        )
                    }
                }
            }
        }

        given("Notice intent") {
            val intent =
                CommandIntent.Notice(
                    targetUserIds = listOf("U1", "U2"),
                    message = "meeting soon",
                )

            `when`("resolveAll is called") {
                val capturedText = slot<String>()
                every {
                    slackEventBuilder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        simpleString = capture(capturedText),
                        commandType = any(),
                    )
                } returns stubEvent

                resolver.resolveAll(
                    intents = listOf(intent),
                    basicInfo = basicInfo,
                    commandType = commandType,
                )

                then("formats Slack mentions and prepends [Notice]") {
                    capturedText.captured shouldBe "[Notice] <@U1> <@U2> meeting soon"
                }
            }
        }

        given("MeetingForm intent") {
            val intent = CommandIntent.MeetingForm(approvalContents = null)

            `when`("resolveAll is called") {
                every {
                    slackEventBuilder.requestMeetingFormRequest(
                        commandBasicInfo = any(),
                        commandType = any(),
                        commandDetailType = any(),
                        approvalContents = any(),
                    )
                } returns stubEvent

                resolver.resolveAll(
                    intents = listOf(intent),
                    basicInfo = basicInfo,
                    commandType = commandType,
                )

                then("calls requestMeetingFormRequest with intent default (REQUEST_MEETING_FORM)") {
                    verify(exactly = 1) {
                        slackEventBuilder.requestMeetingFormRequest(
                            commandBasicInfo = basicInfo,
                            commandType = commandType,
                            commandDetailType = CommandDetailType.REQUEST_MEETING_FORM,
                            approvalContents = null,
                        )
                    }
                }
            }
        }

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
                        commandType = commandType,
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
                        commandType = commandType,
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
                        commandType = commandType,
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
                        commandType = any(),
                        commandDetailType = any(),
                    )
                } returns stubEvent

                resolver.resolveAll(
                    intents = listOf(intent),
                    basicInfo = basicInfo,
                    commandType = commandType,
                )

                then("calls replaceOriginalText with intent default commandDetailType (REPLACE_TEXT)") {
                    verify(exactly = 1) {
                        slackEventBuilder.replaceOriginalText(
                            markdownText = "replacement",
                            responseUrl = "https://hooks.slack.com/foo",
                            commandBasicInfo = basicInfo,
                            commandType = commandType,
                            commandDetailType = CommandDetailType.REPLACE_TEXT,
                        )
                    }
                }
            }
        }

        given("Nothing intent") {
            `when`("resolveAll is called with only Nothing intents") {
                val events =
                    resolver.resolveAll(
                        intents = listOf(CommandIntent.Nothing),
                        basicInfo = basicInfo,
                        commandType = commandType,
                    )

                then("returns empty list and does not invoke any builder method") {
                    events.shouldHaveSize(0)
                }
            }
        }

        given("mixed intents with heterogeneous commandDetailType (regression for intent routing collapse)") {
            `when`("resolveAll is called with ReplaceMessage + ApplyReject in one batch") {
                val replaceSlot = slot<CommandDetailType>()
                val applyRejectSlot = slot<CommandDetailType>()
                every {
                    slackEventBuilder.replaceOriginalText(
                        markdownText = any(),
                        responseUrl = any(),
                        commandBasicInfo = any(),
                        commandType = any(),
                        commandDetailType = capture(replaceSlot),
                    )
                } returns stubEvent
                every {
                    slackEventBuilder.simpleApplyRejectRequest(
                        commandDetailType = capture(applyRejectSlot),
                        commandBasicInfo = any(),
                        approvalContents = any(),
                        commandType = any(),
                        targetUserId = any(),
                    )
                } returns stubEvent

                val approvalContents =
                    ApprovalContents(
                        reason = "notice",
                        publisherId = basicInfo.publisherId,
                        idempotencyKey = basicInfo.idempotencyKey,
                        commandDetailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
                    )
                val intents =
                    listOf(
                        CommandIntent.ReplaceMessage(
                            markdownText = "done",
                            responseUrl = "https://hooks.slack.com/x",
                        ),
                        // ApplyReject derives commandDetailType from approvalContents
                        CommandIntent.ApplyReject(
                            approvalContents = approvalContents,
                            targetUserId = "U_PARTICIPANT",
                        ),
                    )

                resolver.resolveAll(
                    intents = intents,
                    basicInfo = basicInfo,
                    commandType = commandType,
                )

                then("each intent's own commandDetailType is preserved, not collapsed to a single command-level type") {
                    replaceSlot.captured shouldBe CommandDetailType.REPLACE_TEXT
                    applyRejectSlot.captured shouldBe CommandDetailType.MEETING_APPROVAL_NOTICE_FORM
                }
            }
        }

        given("mixed intents") {
            `when`("resolveAll is called with TextResponse + Nothing") {
                every {
                    slackEventBuilder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        simpleString = any(),
                        commandType = any(),
                    )
                } returns stubEvent

                val events =
                    resolver.resolveAll(
                        intents =
                            listOf(
                                CommandIntent.TextResponse(headLine = "h", message = "m"),
                                CommandIntent.Nothing,
                            ),
                        basicInfo = basicInfo,
                        commandType = commandType,
                    )

                then("Nothing is filtered out while TextResponse is resolved") {
                    events shouldHaveSize 1
                }
            }
        }
    })
