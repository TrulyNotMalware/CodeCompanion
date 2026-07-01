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
import dev.notypie.domain.standup.createRoutineDto
import dev.notypie.domain.standup.createStandupSessionDto
import dev.notypie.impl.command.event.createOpenViewEvent
import dev.notypie.impl.command.event.createSendSlackMessageEvent
import dev.notypie.repository.standup.StandupRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class SlackOutboundStagerTest :
    BehaviorSpec({
        val slackEventBuilder = mockk<SlackApiEventConstructor>()
        val standupRepository = mockk<StandupRepository>()
        val stager =
            SlackOutboundStager(
                slackEventBuilder = slackEventBuilder,
                standupRepository = standupRepository,
            )

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

            `when`("stage is called") {
                every {
                    slackEventBuilder.updateNoticeMessageRequest(
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        channel = any(),
                        messageTs = any(),
                        markdownText = any(),
                    )
                } returns stubEvent

                stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to updateNoticeMessageRequest, passing the emitter detailType through") {
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

        given("an UpdateMessage with DECLINE_REASON_MODAL detailType") {
            val message =
                OutboundMessage.UpdateMessage(
                    ref =
                        MessageRef(
                            conversation = ConversationTarget(id = "C_NOTICE"),
                            messageId = "1700000000.000100",
                        ),
                    content = MessageContent.Text(headline = null, markdown = "You declined the meeting."),
                    detailType = CommandDetailType.DECLINE_REASON_MODAL,
                )

            `when`("stage is called") {
                val detailTypeSlot = slot<CommandDetailType>()
                every {
                    slackEventBuilder.updateNoticeMessageRequest(
                        commandBasicInfo = any(),
                        commandDetailType = capture(detailTypeSlot),
                        channel = any(),
                        messageTs = any(),
                        markdownText = any(),
                    )
                } returns stubEvent

                stager.stage(message = message, basicInfo = basicInfo)

                then("the per-emitter detailType passes through unchanged") {
                    detailTypeSlot.captured shouldBe CommandDetailType.DECLINE_REASON_MODAL
                }
            }
        }

        given("a ReplaceMessage") {
            val message =
                OutboundMessage.ReplaceMessage(
                    handle = ResponseReplaceHandle(raw = "https://hooks.slack.com/foo"),
                    content = MessageContent.Text(headline = null, markdown = "replacement"),
                )

            `when`("stage is called") {
                every {
                    slackEventBuilder.replaceOriginalText(
                        markdownText = any(),
                        responseUrl = any(),
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                    )
                } returns stubEvent

                stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to replaceOriginalText with REPLACE_TEXT and the responseUrl from the handle") {
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

        given("an OpenModal with a Reschedule form") {
            val meetingUid = UUID.randomUUID()
            val message =
                OutboundMessage.OpenModal(
                    handle = ModalOpenHandle(raw = "trigger-reschedule"),
                    form =
                        ModalForm.Reschedule(
                            meetingUid = meetingUid,
                            requesterId = "U_HOST",
                            channel = ConversationTarget(id = "C_LIST"),
                        ),
                )

            `when`("stage is called") {
                every {
                    slackEventBuilder.openRescheduleMeetingModalRequest(
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        triggerId = any(),
                        meetingUid = any(),
                        requesterId = any(),
                        channel = any(),
                        currentStartAt = any(),
                    )
                } returns createOpenViewEvent(commandDetailType = CommandDetailType.RESCHEDULE_MEETING)

                stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to openRescheduleMeetingModalRequest with RESCHEDULE_MEETING and the ferried channel") {
                    verify(exactly = 1) {
                        slackEventBuilder.openRescheduleMeetingModalRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.RESCHEDULE_MEETING,
                            triggerId = "trigger-reschedule",
                            meetingUid = meetingUid,
                            requesterId = "U_HOST",
                            channel = "C_LIST",
                            currentStartAt = any(),
                        )
                    }
                }
            }
        }

        given("an OpenModal with a Reschedule form and a blank trigger") {
            val message =
                OutboundMessage.OpenModal(
                    handle = ModalOpenHandle(raw = ""),
                    form =
                        ModalForm.Reschedule(
                            meetingUid = UUID.randomUUID(),
                            requesterId = "U_HOST",
                            channel = ConversationTarget(id = "C_LIST"),
                        ),
                )

            `when`("stage is called") {
                val event = stager.stage(message = message, basicInfo = basicInfo)

                then("a blank trigger yields null (an accidental builder call would throw, unstubbed)") {
                    event shouldBe null
                }
            }
        }

        given("an OpenModal with an AddParticipant form") {
            val meetingUid = UUID.randomUUID()
            val message =
                OutboundMessage.OpenModal(
                    handle = ModalOpenHandle(raw = "trigger-add"),
                    form =
                        ModalForm.AddParticipant(
                            meetingUid = meetingUid,
                            requesterId = "U_HOST",
                            channel = ConversationTarget(id = "C_LIST"),
                        ),
                )

            `when`("stage is called") {
                every {
                    slackEventBuilder.openAddParticipantModalRequest(
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        triggerId = any(),
                        meetingUid = any(),
                        requesterId = any(),
                        channel = any(),
                    )
                } returns createOpenViewEvent(commandDetailType = CommandDetailType.ADD_PARTICIPANT)

                stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to openAddParticipantModalRequest with ADD_PARTICIPANT and the ferried channel") {
                    verify(exactly = 1) {
                        slackEventBuilder.openAddParticipantModalRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.ADD_PARTICIPANT,
                            triggerId = "trigger-add",
                            meetingUid = meetingUid,
                            requesterId = "U_HOST",
                            channel = "C_LIST",
                        )
                    }
                }
            }
        }

        given("an OpenModal with an AddParticipant form and a blank trigger") {
            val message =
                OutboundMessage.OpenModal(
                    handle = ModalOpenHandle(raw = ""),
                    form =
                        ModalForm.AddParticipant(
                            meetingUid = UUID.randomUUID(),
                            requesterId = "U_HOST",
                            channel = ConversationTarget(id = "C_LIST"),
                        ),
                )

            `when`("stage is called") {
                val event = stager.stage(message = message, basicInfo = basicInfo)

                then("a blank trigger yields null (an accidental builder call would throw, unstubbed)") {
                    event shouldBe null
                }
            }
        }

        given("an OpenModal with a StandupSetup form") {
            val message =
                OutboundMessage.OpenModal(
                    handle = ModalOpenHandle(raw = "trigger-setup"),
                    form =
                        ModalForm.StandupSetup(
                            creatorId = "U_CREATOR",
                            commandChannel = ConversationTarget(id = "C_SETUP"),
                        ),
                )

            `when`("stage is called") {
                every {
                    slackEventBuilder.openStandupSetupModalRequest(
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        triggerId = any(),
                        creatorId = any(),
                        commandChannel = any(),
                    )
                } returns createOpenViewEvent(commandDetailType = CommandDetailType.STANDUP_SETUP_FORM)

                stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to openStandupSetupModalRequest with STANDUP_SETUP_FORM and the ferried channel") {
                    verify(exactly = 1) {
                        slackEventBuilder.openStandupSetupModalRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.STANDUP_SETUP_FORM,
                            triggerId = "trigger-setup",
                            creatorId = "U_CREATOR",
                            commandChannel = "C_SETUP",
                        )
                    }
                }
            }
        }

        given("an OpenModal with a StandupSetup form and a blank trigger") {
            val message =
                OutboundMessage.OpenModal(
                    handle = ModalOpenHandle(raw = ""),
                    form =
                        ModalForm.StandupSetup(
                            creatorId = "U_CREATOR",
                            commandChannel = ConversationTarget(id = "C_SETUP"),
                        ),
                )

            `when`("stage is called") {
                val event = stager.stage(message = message, basicInfo = basicInfo)

                then("a blank trigger yields null (an accidental builder call would throw, unstubbed)") {
                    event shouldBe null
                }
            }
        }

        given("an OpenModal with a StandupFill form") {
            val routineUid = UUID.randomUUID()
            val sessionUid = UUID.randomUUID()
            val sessionDate = LocalDate.of(2026, 5, 4)
            val message =
                OutboundMessage.OpenModal(
                    handle = ModalOpenHandle(raw = "trigger-fill"),
                    form =
                        ModalForm.StandupFill(
                            sessionUid = sessionUid,
                            routineUid = routineUid,
                            requesterId = "U_STANDUP",
                            originNotice =
                                MessageRef(
                                    conversation = ConversationTarget(id = "D_NOTICE"),
                                    messageId = "1700000000.000600",
                                ),
                        ),
                )

            `when`("stage is called with an existing session") {
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
                } returns createOpenViewEvent(commandDetailType = CommandDetailType.STANDUP_FILL)

                stager.stage(message = message, basicInfo = basicInfo)

                then("loads routine/session and delegates to openStandupModalRequest with STANDUP_FILL") {
                    verify(exactly = 1) {
                        slackEventBuilder.openStandupModalRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.STANDUP_FILL,
                            triggerId = "trigger-fill",
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

        given("an OpenModal with a StandupFill form whose session is missing") {
            val routineUid = UUID.randomUUID()
            val sessionUid = UUID.randomUUID()
            val message =
                OutboundMessage.OpenModal(
                    handle = ModalOpenHandle(raw = "trigger-fill"),
                    form =
                        ModalForm.StandupFill(
                            sessionUid = sessionUid,
                            routineUid = routineUid,
                            requesterId = "U_STANDUP",
                            originNotice =
                                MessageRef(
                                    conversation = ConversationTarget(id = "D_NOTICE"),
                                    messageId = "1700000000.000600",
                                ),
                        ),
                )

            `when`("stage is called") {
                every { standupRepository.getRoutine(routineUid = routineUid) } returns
                    createRoutineDto(routineUid = routineUid)
                every { standupRepository.findSession(sessionUid = sessionUid) } returns null

                val event = stager.stage(message = message, basicInfo = basicInfo)

                then("a missing session yields null (an accidental modal open would throw, unstubbed)") {
                    event shouldBe null
                }
            }
        }

        given("an OpenModal with a StandupFill form and a blank trigger") {
            val message =
                OutboundMessage.OpenModal(
                    handle = ModalOpenHandle(raw = ""),
                    form =
                        ModalForm.StandupFill(
                            sessionUid = UUID.randomUUID(),
                            routineUid = UUID.randomUUID(),
                            requesterId = "U_STANDUP",
                            originNotice =
                                MessageRef(
                                    conversation = ConversationTarget(id = "D_NOTICE"),
                                    messageId = "1700000000.000600",
                                ),
                        ),
                )

            `when`("stage is called") {
                val event = stager.stage(message = message, basicInfo = basicInfo)

                then("a blank trigger yields null without touching the repository (unstubbed calls would throw)") {
                    event shouldBe null
                }
            }
        }

        given("an OpenModal with a DeclineReason form") {
            val meetingKey = UUID.randomUUID()
            val message =
                OutboundMessage.OpenModal(
                    handle = ModalOpenHandle(raw = "trigger-decline"),
                    form =
                        ModalForm.DeclineReason(
                            meetingIdempotencyKey = meetingKey,
                            participantUserId = "U_PARTICIPANT",
                            meetingTitle = "Weekly sync",
                            originNotice =
                                MessageRef(
                                    conversation = ConversationTarget(id = "C_NOTICE"),
                                    messageId = "1700000000.000200",
                                ),
                        ),
                )

            `when`("stage is called") {
                every {
                    slackEventBuilder.openDeclineReasonModalRequest(
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        triggerId = any(),
                        meetingIdempotencyKey = any(),
                        participantUserId = any(),
                        meetingTitle = any(),
                        noticeChannel = any(),
                        noticeMessageTs = any(),
                    )
                } returns createOpenViewEvent(commandDetailType = CommandDetailType.DECLINE_REASON_MODAL)

                stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to openDeclineReasonModalRequest with DECLINE_REASON_MODAL and the notice ref") {
                    verify(exactly = 1) {
                        slackEventBuilder.openDeclineReasonModalRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.DECLINE_REASON_MODAL,
                            triggerId = "trigger-decline",
                            meetingIdempotencyKey = meetingKey,
                            participantUserId = "U_PARTICIPANT",
                            meetingTitle = "Weekly sync",
                            noticeChannel = "C_NOTICE",
                            noticeMessageTs = "1700000000.000200",
                        )
                    }
                }
            }
        }

        given("an OpenModal with a DeclineReason form and a blank trigger (no guard)") {
            val meetingKey = UUID.randomUUID()
            val message =
                OutboundMessage.OpenModal(
                    handle = ModalOpenHandle(raw = ""),
                    form =
                        ModalForm.DeclineReason(
                            meetingIdempotencyKey = meetingKey,
                            participantUserId = "U_PARTICIPANT",
                            meetingTitle = "",
                            originNotice = null,
                        ),
                )

            `when`("stage is called") {
                every {
                    slackEventBuilder.openDeclineReasonModalRequest(
                        commandBasicInfo = any(),
                        commandDetailType = any(),
                        triggerId = any(),
                        meetingIdempotencyKey = any(),
                        participantUserId = any(),
                        meetingTitle = any(),
                        noticeChannel = any(),
                        noticeMessageTs = any(),
                    )
                } returns createOpenViewEvent(commandDetailType = CommandDetailType.DECLINE_REASON_MODAL)

                stager.stage(message = message, basicInfo = basicInfo)

                then("DeclineReason has NO blank-trigger guard: the blank triggerId passes straight through") {
                    verify(exactly = 1) {
                        slackEventBuilder.openDeclineReasonModalRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.DECLINE_REASON_MODAL,
                            triggerId = "",
                            meetingIdempotencyKey = meetingKey,
                            participantUserId = "U_PARTICIPANT",
                            meetingTitle = "",
                            noticeChannel = "",
                            noticeMessageTs = "",
                        )
                    }
                }
            }
        }
    })
