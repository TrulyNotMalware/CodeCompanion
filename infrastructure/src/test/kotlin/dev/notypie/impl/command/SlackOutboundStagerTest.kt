package dev.notypie.impl.command

import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.dto.modals.ApprovalContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.ModalForm
import dev.notypie.domain.command.outbound.ModalOpenHandle
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.standup.createRoutineDto
import dev.notypie.domain.standup.createStandupSessionDto
import dev.notypie.impl.command.event.OutboundMessageEnqueued
import dev.notypie.impl.command.event.createOpenViewEvent
import dev.notypie.repository.standup.StandupRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
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
        val target = ConversationTarget(id = basicInfo.channel)

        // Non-modal families are never rendered by the stager; they are wrapped transport-neutral
        // and rendered at deliver time (that path is covered by SlackOutboundRendererTest). A strict
        // slackEventBuilder mock means an accidental render call here would throw, unstubbed.
        given("a ChannelMessage with Text content") {
            val message =
                OutboundMessage.ChannelMessage(
                    target = target,
                    content = MessageContent.Text(headline = "hi", markdown = "hello world"),
                    detailType = CommandDetailType.DAILY_AGENDA,
                )

            `when`("stage is called") {
                val event = stager.stage(message = message, basicInfo = basicInfo)

                then("it is enqueued transport-neutral, carrying the message and command context") {
                    val enqueued = event.shouldBeInstanceOf<OutboundMessageEnqueued>()
                    enqueued.payload.message shouldBe message
                    enqueued.payload.basicInfo shouldBe basicInfo
                    enqueued.idempotencyKey shouldBe basicInfo.idempotencyKey
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
                val event = stager.stage(message = message, basicInfo = basicInfo)

                then("it is enqueued transport-neutral without touching the renderer") {
                    event.shouldBeInstanceOf<OutboundMessageEnqueued>().payload.message shouldBe message
                }
            }
        }

        given("an Approval") {
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

            `when`("stage is called") {
                val event = stager.stage(message = message, basicInfo = basicInfo)

                then("it is enqueued transport-neutral") {
                    event.shouldBeInstanceOf<OutboundMessageEnqueued>().payload.message shouldBe message
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
                } returns createOpenViewEvent(commandDetailType = CommandDetailType.MEETING_RESCHEDULE_REQUEST)

                stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to openRescheduleMeetingModalRequest with the ferried channel") {
                    verify(exactly = 1) {
                        slackEventBuilder.openRescheduleMeetingModalRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.MEETING_RESCHEDULE_REQUEST,
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
                } returns createOpenViewEvent(commandDetailType = CommandDetailType.MEETING_ADD_PARTICIPANT_REQUEST)

                stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to openAddParticipantModalRequest with the ferried channel") {
                    verify(exactly = 1) {
                        slackEventBuilder.openAddParticipantModalRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.MEETING_ADD_PARTICIPANT_REQUEST,
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
                } returns createOpenViewEvent(commandDetailType = CommandDetailType.STANDUP_SETUP_REQUEST)

                stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to openStandupSetupModalRequest with STANDUP_SETUP_REQUEST and the ferried channel") {
                    verify(exactly = 1) {
                        slackEventBuilder.openStandupSetupModalRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.STANDUP_SETUP_REQUEST,
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
                } returns createOpenViewEvent(commandDetailType = CommandDetailType.STANDUP_PROMPT)

                stager.stage(message = message, basicInfo = basicInfo)

                then("loads routine/session and delegates to openStandupModalRequest with STANDUP_PROMPT") {
                    verify(exactly = 1) {
                        slackEventBuilder.openStandupModalRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.STANDUP_PROMPT,
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
                } returns createOpenViewEvent(commandDetailType = CommandDetailType.MEETING_DECLINE_REASON)

                stager.stage(message = message, basicInfo = basicInfo)

                then("delegates to openDeclineReasonModalRequest with MEETING_DECLINE_REASON and the notice ref") {
                    verify(exactly = 1) {
                        slackEventBuilder.openDeclineReasonModalRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.MEETING_DECLINE_REASON,
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
                } returns createOpenViewEvent(commandDetailType = CommandDetailType.MEETING_DECLINE_REASON)

                stager.stage(message = message, basicInfo = basicInfo)

                then("DeclineReason has NO blank-trigger guard: the blank triggerId passes straight through") {
                    verify(exactly = 1) {
                        slackEventBuilder.openDeclineReasonModalRequest(
                            commandBasicInfo = basicInfo,
                            commandDetailType = CommandDetailType.MEETING_DECLINE_REASON,
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
