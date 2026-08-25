package dev.notypie.impl.command

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectBoxDetails
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.ModalOpenHandle
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.ResponseReplaceHandle
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.meet.createMeetingDto
import dev.notypie.domain.standup.createRoutineMemberDto
import dev.notypie.impl.command.event.createSendSlackMessageEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class SlackOutboundRendererTest :
    BehaviorSpec({
        val slackEventBuilder = mockk<SlackApiEventConstructor>()
        val renderer = SlackOutboundRenderer(slackEventBuilder = slackEventBuilder)

        val basicInfo = createCommandBasicInfo()
        val stubEvent =
            createSendSlackMessageEvent(
                commandDetailType = CommandDetailType.SIMPLE_TEXT,
                idempotencyKey = basicInfo.idempotencyKey,
            )
        val target = ConversationTarget(id = basicInfo.channel)

        given("a ChannelMessage with Text content") {
            val message =
                OutboundMessage.ChannelMessage(
                    target = target,
                    content = MessageContent.Text(headline = "hi", markdown = "hello world"),
                )

            `when`("render is called") {
                every {
                    slackEventBuilder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        simpleString = any(),
                    )
                } returns stubEvent

                val payload = renderer.render(message = message, basicInfo = basicInfo)

                then("delegates to simpleTextRequest with SIMPLE_TEXT and returns the event payload") {
                    payload shouldBe stubEvent.payload
                    verify(exactly = 1) {
                        slackEventBuilder.simpleTextRequest(
                            commandDetailType = CommandDetailType.SIMPLE_TEXT,
                            headLineText = "hi",
                            commandBasicInfo = basicInfo,
                            simpleString = "hello world",
                        )
                    }
                }
            }
        }

        given("a ChannelMessage with Text content and no headline") {
            val message =
                OutboundMessage.ChannelMessage(
                    target = target,
                    content = MessageContent.Text(headline = null, markdown = "body"),
                )

            `when`("render is called") {
                val headlineSlot = slot<String>()
                every {
                    slackEventBuilder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = capture(headlineSlot),
                        commandBasicInfo = any(),
                        simpleString = any(),
                    )
                } returns stubEvent

                renderer.render(message = message, basicInfo = basicInfo)

                then("a null headline renders as an empty string") {
                    headlineSlot.captured shouldBe ""
                }
            }
        }

        given("a ChannelMessage with Text content and a per-emitter detailType") {
            val message =
                OutboundMessage.ChannelMessage(
                    target = target,
                    content = MessageContent.Text(headline = "Daily agenda", markdown = "agenda body"),
                    detailType = CommandDetailType.DAILY_AGENDA,
                )

            `when`("render is called") {
                every {
                    slackEventBuilder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        simpleString = any(),
                    )
                } returns stubEvent

                val payload = renderer.render(message = message, basicInfo = basicInfo)

                then("the emitter's detailType overrides the SIMPLE_TEXT default") {
                    payload shouldBe stubEvent.payload
                    verify(exactly = 1) {
                        slackEventBuilder.simpleTextRequest(
                            commandDetailType = CommandDetailType.DAILY_AGENDA,
                            headLineText = "Daily agenda",
                            commandBasicInfo = basicInfo,
                            simpleString = "agenda body",
                        )
                    }
                }
            }
        }

        given("a ChannelMessage with Text content anchored to a thread") {
            val message =
                OutboundMessage.ChannelMessage(
                    target = target,
                    content = MessageContent.Text(headline = "AI assistant", markdown = "threaded reply"),
                    detailType = CommandDetailType.AGENT_CONVERSE,
                    threadId = "1700000000.000100",
                )

            `when`("render is called") {
                every {
                    slackEventBuilder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        simpleString = any(),
                        threadTs = any(),
                    )
                } returns stubEvent

                val payload = renderer.render(message = message, basicInfo = basicInfo)

                then("the neutral threadId is delivered as the Slack thread_ts") {
                    payload shouldBe stubEvent.payload
                    verify(exactly = 1) {
                        slackEventBuilder.simpleTextRequest(
                            commandDetailType = CommandDetailType.AGENT_CONVERSE,
                            headLineText = "AI assistant",
                            commandBasicInfo = basicInfo,
                            simpleString = "threaded reply",
                            threadTs = "1700000000.000100",
                        )
                    }
                }
            }
        }

        given("a ChannelMessage with ErrorNotice content") {
            val message =
                OutboundMessage.ChannelMessage(
                    target = target,
                    content =
                        MessageContent.ErrorNotice(
                            className = "TestException",
                            message = "something broke",
                            details = "stack trace",
                        ),
                )

            `when`("render is called") {
                every {
                    slackEventBuilder.detailErrorTextRequest(
                        commandDetailType = any(),
                        errorClassName = any(),
                        errorMessage = any(),
                        details = any(),
                        commandBasicInfo = any(),
                    )
                } returns stubEvent

                val payload = renderer.render(message = message, basicInfo = basicInfo)

                then("delegates to detailErrorTextRequest with ERROR_RESPONSE") {
                    payload shouldBe stubEvent.payload
                    verify(exactly = 1) {
                        slackEventBuilder.detailErrorTextRequest(
                            commandDetailType = CommandDetailType.ERROR_RESPONSE,
                            errorClassName = "TestException",
                            errorMessage = "something broke",
                            details = "stack trace",
                            commandBasicInfo = basicInfo,
                        )
                    }
                }
            }
        }

        given("a ChannelMessage with Schedule content") {
            val scheduleInfo =
                TimeScheduleInfo(
                    scheduleName = "standup",
                    startTime = LocalDateTime.now(),
                    endTime = LocalDateTime.now().plusHours(1),
                )
            val message =
                OutboundMessage.ChannelMessage(
                    target = target,
                    content = MessageContent.Schedule(headline = "daily", info = scheduleInfo),
                )

            `when`("render is called") {
                every {
                    slackEventBuilder.simpleTimeScheduleRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        timeScheduleInfo = any(),
                    )
                } returns stubEvent

                val payload = renderer.render(message = message, basicInfo = basicInfo)

                then("delegates to simpleTimeScheduleRequest with SIMPLE_TEXT") {
                    payload shouldBe stubEvent.payload
                    verify(exactly = 1) {
                        slackEventBuilder.simpleTimeScheduleRequest(
                            commandDetailType = CommandDetailType.SIMPLE_TEXT,
                            headLineText = "daily",
                            commandBasicInfo = basicInfo,
                            timeScheduleInfo = scheduleInfo,
                        )
                    }
                }
            }
        }

        given("a ChannelMessage with Form content") {
            val fields =
                listOf(
                    SelectionContents(
                        title = "Purpose",
                        explanation = "Select",
                        placeholderText = "pick one",
                        contents = listOf(SelectBoxDetails(name = "A", value = "a")),
                    ),
                )
            val reason = TextInputContents(title = "Reason", placeholderText = "why")
            val message =
                OutboundMessage.ChannelMessage(
                    target = target,
                    content =
                        MessageContent.Form(
                            headline = "Approve",
                            fields = fields,
                            reason = reason,
                            approval = null,
                        ),
                )

            `when`("render is called") {
                every {
                    slackEventBuilder.simpleApprovalFormRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        selectionFields = any(),
                        reasonInput = any(),
                        approvalContents = any(),
                    )
                } returns stubEvent

                val payload = renderer.render(message = message, basicInfo = basicInfo)

                then("delegates to simpleApprovalFormRequest with APPROVAL_REQUEST and the same fields") {
                    payload shouldBe stubEvent.payload
                    verify(exactly = 1) {
                        slackEventBuilder.simpleApprovalFormRequest(
                            commandDetailType = CommandDetailType.APPROVAL_REQUEST,
                            headLineText = "Approve",
                            commandBasicInfo = basicInfo,
                            selectionFields = fields,
                            reasonInput = reason,
                            approvalContents = null,
                        )
                    }
                }
            }
        }

        given("a ChannelMessage with MeetingRequest content") {
            val message =
                OutboundMessage.ChannelMessage(
                    target = target,
                    content = MessageContent.MeetingRequest(approval = null),
                )

            `when`("render is called") {
                every {
                    slackEventBuilder.requestMeetingFormRequest(
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        approvalContents = any(),
                    )
                } returns stubEvent

                val payload = renderer.render(message = message, basicInfo = basicInfo)

                then("delegates to requestMeetingFormRequest with MEETING_CREATE_REQUEST") {
                    payload shouldBe stubEvent.payload
                    verify(exactly = 1) {
                        slackEventBuilder.requestMeetingFormRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.MEETING_CREATE_REQUEST,
                            approvalContents = null,
                        )
                    }
                }
            }
        }

        given("a ChannelMessage with StandupSummary content") {
            val members = listOf(createRoutineMemberDto())
            val message =
                OutboundMessage.ChannelMessage(
                    target = target,
                    content =
                        MessageContent.StandupSummary(
                            routineName = "Daily Standup",
                            sessionDate = LocalDate.of(2026, 5, 1),
                            members = members,
                            answers = emptyList(),
                            questions = listOf("What did you do yesterday?"),
                        ),
                )

            `when`("render is called") {
                every {
                    slackEventBuilder.standupSummaryRequest(
                        commandBasicInfo = any(),
                        routineName = any(),
                        sessionDate = any(),
                        members = any(),
                        answers = any(),
                        questions = any(),
                    )
                } returns stubEvent

                val payload = renderer.render(message = message, basicInfo = basicInfo)

                then("delegates to standupSummaryRequest with the summary fields") {
                    payload shouldBe stubEvent.payload
                    verify(exactly = 1) {
                        slackEventBuilder.standupSummaryRequest(
                            commandBasicInfo = basicInfo,
                            routineName = "Daily Standup",
                            sessionDate = LocalDate.of(2026, 5, 1),
                            members = members,
                            answers = emptyList(),
                            questions = listOf("What did you do yesterday?"),
                        )
                    }
                }
            }
        }

        given("an Ephemeral with a recipient") {
            val message =
                OutboundMessage.Ephemeral(
                    target = target,
                    recipient = UserRef(id = "U_TARGET"),
                    content = MessageContent.Text(headline = null, markdown = "secret"),
                )

            `when`("render is called") {
                every {
                    slackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = any(),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        targetUserId = any(),
                    )
                } returns stubEvent

                val payload = renderer.render(message = message, basicInfo = basicInfo)

                then("delegates to simpleEphemeralTextRequest with the recipient id") {
                    payload shouldBe stubEvent.payload
                    verify(exactly = 1) {
                        slackEventBuilder.simpleEphemeralTextRequest(
                            textMessage = "secret",
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.SIMPLE_TEXT,
                            targetUserId = "U_TARGET",
                        )
                    }
                }
            }
        }

        given("an Ephemeral with a null recipient (the command publisher)") {
            val message =
                OutboundMessage.Ephemeral(
                    target = target,
                    recipient = null,
                    content = MessageContent.Text(headline = null, markdown = "to publisher"),
                )

            `when`("render is called") {
                every {
                    slackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = any(),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        targetUserId = any(),
                    )
                } returns stubEvent

                renderer.render(message = message, basicInfo = basicInfo)

                then("a null recipient maps to a null targetUserId (posts to the publisher)") {
                    verify(exactly = 1) {
                        slackEventBuilder.simpleEphemeralTextRequest(
                            textMessage = "to publisher",
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.SIMPLE_TEXT,
                            targetUserId = null,
                        )
                    }
                }
            }
        }

        given("an Ephemeral with a per-emitter detailType") {
            val message =
                OutboundMessage.Ephemeral(
                    target = target,
                    recipient = UserRef(id = "U_REQUESTER"),
                    content = MessageContent.Text(headline = null, markdown = "canceled"),
                    detailType = CommandDetailType.CANCEL_MEETING,
                )

            `when`("render is called") {
                every {
                    slackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = any(),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        targetUserId = any(),
                    )
                } returns stubEvent

                val payload = renderer.render(message = message, basicInfo = basicInfo)

                then("the emitter's detailType overrides the SIMPLE_TEXT default") {
                    payload shouldBe stubEvent.payload
                    verify(exactly = 1) {
                        slackEventBuilder.simpleEphemeralTextRequest(
                            textMessage = "canceled",
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.CANCEL_MEETING,
                            targetUserId = "U_REQUESTER",
                        )
                    }
                }
            }
        }

        given("an Ephemeral with MeetingList content") {
            val meetings = listOf(createMeetingDto())
            val message =
                OutboundMessage.Ephemeral(
                    target = target,
                    content = MessageContent.MeetingList(meetings = meetings, currentUserId = "U_VIEWER"),
                )

            `when`("render is called") {
                every {
                    slackEventBuilder.getMeetingListFormRequest(
                        myMeetings = any(),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        currentUserId = any(),
                    )
                } returns stubEvent

                val payload = renderer.render(message = message, basicInfo = basicInfo)

                then("delegates to getMeetingListFormRequest with GET_MEETING_LIST by default") {
                    payload shouldBe stubEvent.payload
                    verify(exactly = 1) {
                        slackEventBuilder.getMeetingListFormRequest(
                            myMeetings = meetings,
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.GET_MEETING_LIST,
                            currentUserId = "U_VIEWER",
                        )
                    }
                }
            }
        }

        given("an Approval with a recipient and a subTitle") {
            val approval =
                ApprovalContents(
                    reason = "approve this",
                    subTitle = "Sprint Planning",
                    publisherId = basicInfo.publisherId,
                    idempotencyKey = basicInfo.idempotencyKey,
                    commandDetailType = CommandDetailType.MEETING_APPROVAL_REQUEST,
                )
            val message =
                OutboundMessage.Approval(
                    target = target,
                    recipient = UserRef(id = "U_PARTICIPANT"),
                    approval = approval,
                )

            `when`("render is called") {
                every {
                    slackEventBuilder.simpleApplyRejectRequest(
                        commandDetailType = any(),
                        commandBasicInfo = any(),
                        approvalContents = any(),
                        targetUserId = any(),
                        routingExtras = any(),
                    )
                } returns stubEvent

                val payload = renderer.render(message = message, basicInfo = basicInfo)

                then("delegates to simpleApplyRejectRequest, deriving the detail type and routing the subTitle") {
                    payload shouldBe stubEvent.payload
                    verify(exactly = 1) {
                        slackEventBuilder.simpleApplyRejectRequest(
                            commandDetailType = CommandDetailType.MEETING_APPROVAL_REQUEST,
                            commandBasicInfo = basicInfo,
                            approvalContents = approval,
                            targetUserId = "U_PARTICIPANT",
                            routingExtras = listOf("Sprint Planning"),
                        )
                    }
                }
            }
        }

        given("an Approval with a blank subTitle and no recipient") {
            val approval =
                ApprovalContents(
                    reason = "approve this",
                    publisherId = basicInfo.publisherId,
                    idempotencyKey = basicInfo.idempotencyKey,
                    commandDetailType = CommandDetailType.APPLY_REQUEST,
                )
            val message =
                OutboundMessage.Approval(
                    target = target,
                    recipient = null,
                    approval = approval,
                )

            `when`("render is called") {
                val routingSlot = slot<List<String>>()
                every {
                    slackEventBuilder.simpleApplyRejectRequest(
                        commandDetailType = any(),
                        commandBasicInfo = any(),
                        approvalContents = any(),
                        targetUserId = any(),
                        routingExtras = capture(routingSlot),
                    )
                } returns stubEvent

                renderer.render(message = message, basicInfo = basicInfo)

                then("a blank subTitle is filtered out and a null recipient maps to a null targetUserId") {
                    routingSlot.captured shouldBe emptyList()
                    verify(exactly = 1) {
                        slackEventBuilder.simpleApplyRejectRequest(
                            commandDetailType = CommandDetailType.APPLY_REQUEST,
                            commandBasicInfo = basicInfo,
                            approvalContents = approval,
                            targetUserId = null,
                            routingExtras = emptyList(),
                        )
                    }
                }
            }
        }

        given("a Notice") {
            val message =
                OutboundMessage.Notice(
                    target = target,
                    mentions = listOf(UserRef(id = "U1"), UserRef(id = "U2")),
                    message = "meeting soon",
                )

            `when`("render is called") {
                val capturedText = slot<String>()
                val capturedHeadline = slot<String>()
                every {
                    slackEventBuilder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = capture(capturedHeadline),
                        commandBasicInfo = any(),
                        simpleString = capture(capturedText),
                    )
                } returns stubEvent

                renderer.render(message = message, basicInfo = basicInfo)

                then("formats Slack mentions, prepends [Notice], and uses the Notice! headline") {
                    capturedHeadline.captured shouldBe "Notice!"
                    capturedText.captured shouldBe "[Notice] <@U1> <@U2> meeting soon"
                }
            }
        }

        given("an UpdateMessage with STANDUP_ANSWER_SUBMIT detailType") {
            val message =
                OutboundMessage.UpdateMessage(
                    ref =
                        MessageRef(
                            conversation = ConversationTarget(id = "D_NOTICE"),
                            messageId = "1700000000.000300",
                        ),
                    content = MessageContent.Text(headline = null, markdown = "Standup submitted."),
                    detailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                )

            `when`("render is called") {
                every {
                    slackEventBuilder.updateNoticeMessageRequest(
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        channel = any(),
                        messageTs = any(),
                        markdownText = any(),
                    )
                } returns stubEvent

                val payload = renderer.render(message = message, basicInfo = basicInfo)

                then("delegates to updateNoticeMessageRequest, passing the emitter detailType through") {
                    payload shouldBe stubEvent.payload
                    verify(exactly = 1) {
                        slackEventBuilder.updateNoticeMessageRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                            channel = "D_NOTICE",
                            messageTs = "1700000000.000300",
                            markdownText = "Standup submitted.",
                        )
                    }
                }
            }
        }

        given("a ReplaceMessage") {
            val message =
                OutboundMessage.ReplaceMessage(
                    handle = ResponseReplaceHandle(raw = "https://hooks.slack.com/foo"),
                    content = MessageContent.Text(headline = null, markdown = "replacement"),
                )

            `when`("render is called") {
                every {
                    slackEventBuilder.replaceOriginalText(
                        markdownText = any(),
                        responseUrl = any(),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                    )
                } returns stubEvent

                val payload = renderer.render(message = message, basicInfo = basicInfo)

                then("delegates to replaceOriginalText with REPLACE_TEXT and the responseUrl from the handle") {
                    payload shouldBe stubEvent.payload
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

        given("an OpenModal message") {
            val message =
                OutboundMessage.OpenModal(
                    handle = ModalOpenHandle(raw = "trigger-reschedule"),
                    form =
                        ModalForm.Reschedule(
                            meetingUid = UUID.randomUUID(),
                            requesterId = "U_HOST",
                            channel = ConversationTarget(id = "C_LIST"),
                        ),
                )

            `when`("render is called") {
                then("modal opening is not a renderer concern and fails loudly") {
                    shouldThrow<IllegalStateException> {
                        renderer.render(message = message, basicInfo = basicInfo)
                    }
                }
            }
        }

        given("a DirectMessage message") {
            val message =
                OutboundMessage.DirectMessage(
                    recipient = UserRef(id = "U_DM"),
                    content = MessageContent.Text(headline = null, markdown = "hi"),
                )

            `when`("render is called") {
                then("direct messaging is not a renderer concern and fails loudly") {
                    shouldThrow<IllegalStateException> {
                        renderer.render(message = message, basicInfo = basicInfo)
                    }
                }
            }
        }
    })
