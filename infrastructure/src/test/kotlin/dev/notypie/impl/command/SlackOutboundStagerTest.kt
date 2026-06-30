package dev.notypie.impl.command

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createSendSlackMessageEvent
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.dto.modals.SelectBoxDetails
import dev.notypie.domain.command.dto.modals.SelectionContents
import dev.notypie.domain.command.dto.modals.TextInputContents
import dev.notypie.domain.command.dto.modals.TimeScheduleInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.UserRef
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDateTime

class SlackOutboundStagerTest :
    BehaviorSpec({
        val slackEventBuilder = mockk<SlackApiEventConstructor>()
        val stager = SlackOutboundStager(slackEventBuilder = slackEventBuilder)

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

            `when`("stage is called") {
                every {
                    slackEventBuilder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        simpleString = any(),
                    )
                } returns stubEvent

                val event = stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to simpleTextRequest with SIMPLE_TEXT and the same fields") {
                    event shouldBe stubEvent
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

            `when`("stage is called") {
                val headlineSlot = slot<String>()
                every {
                    slackEventBuilder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = capture(headlineSlot),
                        commandBasicInfo = any(),
                        simpleString = any(),
                    )
                } returns stubEvent

                stager.stage(message = message, basicInfo = basicInfo)

                then("a null headline renders as an empty string") {
                    headlineSlot.captured shouldBe ""
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

            `when`("stage is called") {
                every {
                    slackEventBuilder.detailErrorTextRequest(
                        commandDetailType = any(),
                        errorClassName = any(),
                        errorMessage = any(),
                        details = any(),
                        commandBasicInfo = any(),
                    )
                } returns stubEvent

                stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to detailErrorTextRequest with ERROR_RESPONSE") {
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

            `when`("stage is called") {
                every {
                    slackEventBuilder.simpleTimeScheduleRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        timeScheduleInfo = any(),
                    )
                } returns stubEvent

                stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to simpleTimeScheduleRequest with SIMPLE_TEXT") {
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

        given("an Ephemeral with a recipient") {
            val message =
                OutboundMessage.Ephemeral(
                    target = target,
                    recipient = UserRef(id = "U_TARGET"),
                    content = MessageContent.Text(headline = null, markdown = "secret"),
                )

            `when`("stage is called") {
                every {
                    slackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = any(),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        targetUserId = any(),
                    )
                } returns stubEvent

                stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to simpleEphemeralTextRequest with the recipient id") {
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

            `when`("stage is called") {
                every {
                    slackEventBuilder.simpleEphemeralTextRequest(
                        textMessage = any(),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        targetUserId = any(),
                    )
                } returns stubEvent

                stager.stage(message = message, basicInfo = basicInfo)

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

        given("a Notice") {
            val message =
                OutboundMessage.Notice(
                    target = target,
                    mentions = listOf(UserRef(id = "U1"), UserRef(id = "U2")),
                    message = "meeting soon",
                )

            `when`("stage is called") {
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

                stager.stage(message = message, basicInfo = basicInfo)

                then("formats Slack mentions, prepends [Notice], and uses the Notice! headline") {
                    capturedHeadline.captured shouldBe "Notice!"
                    capturedText.captured shouldBe "[Notice] <@U1> <@U2> meeting soon"
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
                    commandDetailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
                )
            val message =
                OutboundMessage.Approval(
                    target = target,
                    recipient = UserRef(id = "U_PARTICIPANT"),
                    approval = approval,
                )

            `when`("stage is called") {
                every {
                    slackEventBuilder.simpleApplyRejectRequest(
                        commandDetailType = any(),
                        commandBasicInfo = any(),
                        approvalContents = any(),
                        targetUserId = any(),
                        routingExtras = any(),
                    )
                } returns stubEvent

                stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to simpleApplyRejectRequest, deriving the detail type and routing the subTitle") {
                    verify(exactly = 1) {
                        slackEventBuilder.simpleApplyRejectRequest(
                            commandDetailType = CommandDetailType.MEETING_APPROVAL_NOTICE_FORM,
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
                    commandDetailType = CommandDetailType.REQUEST_APPLY_FORM,
                )
            val message =
                OutboundMessage.Approval(
                    target = target,
                    recipient = null,
                    approval = approval,
                )

            `when`("stage is called") {
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

                stager.stage(message = message, basicInfo = basicInfo)

                then("a blank subTitle is filtered out and a null recipient maps to a null targetUserId") {
                    routingSlot.captured shouldBe emptyList()
                    verify(exactly = 1) {
                        slackEventBuilder.simpleApplyRejectRequest(
                            commandDetailType = CommandDetailType.REQUEST_APPLY_FORM,
                            commandBasicInfo = basicInfo,
                            approvalContents = approval,
                            targetUserId = null,
                            routingExtras = emptyList(),
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

            `when`("stage is called") {
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

                stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to simpleApprovalFormRequest with APPROVAL_FORM and the same fields") {
                    verify(exactly = 1) {
                        slackEventBuilder.simpleApprovalFormRequest(
                            commandDetailType = CommandDetailType.APPROVAL_FORM,
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

            `when`("stage is called") {
                every {
                    slackEventBuilder.requestMeetingFormRequest(
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        approvalContents = any(),
                    )
                } returns stubEvent

                stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to requestMeetingFormRequest with REQUEST_MEETING_FORM") {
                    verify(exactly = 1) {
                        slackEventBuilder.requestMeetingFormRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.REQUEST_MEETING_FORM,
                            approvalContents = null,
                        )
                    }
                }
            }
        }
    })
