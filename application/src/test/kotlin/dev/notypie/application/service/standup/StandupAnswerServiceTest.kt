package dev.notypie.application.service.standup

import dev.notypie.domain.command.DefaultEventQueue
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.RecordStandupAnswerEvent
import dev.notypie.domain.command.entity.event.RecordStandupAnswerPayload
import dev.notypie.domain.command.entity.event.StandupModalOpenFailedEvent
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.command.event.SendSlackMessageEvent
import dev.notypie.repository.standup.AnswerRecordResult
import dev.notypie.repository.standup.StandupRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class StandupAnswerServiceTest :
    BehaviorSpec({
        given("recordAnswer") {
            val now = Instant.parse("2026-05-01T02:00:00Z")
            val notice = MessageRef(conversation = ConversationTarget(id = "D_NOTICE"), messageId = "1700000000.000500")

            fun eventOf(sessionUid: UUID, notice: MessageRef?) =
                RecordStandupAnswerEvent(
                    idempotencyKey = UUID.randomUUID(),
                    payload =
                        RecordStandupAnswerPayload(
                            sessionUid = sessionUid,
                            userId = "U_STANDUP",
                            responses = listOf("Done", "Next"),
                            notice = notice,
                        ),
                    type = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                )

            fun serviceOf(repo: StandupRepository, stager: OutboundMessageStager, eventPublisher: EventPublisher) =
                StandupAnswerService(
                    standupRepository = repo,
                    outboundStager = stager,
                    eventPublisher = eventPublisher,
                    clock = Clock.fixed(now, ZoneOffset.UTC),
                )

            fun updateTo(text: String): OutboundMessage.UpdateMessage =
                OutboundMessage.UpdateMessage(
                    ref = notice,
                    content = MessageContent.Text(headline = null, markdown = text),
                    detailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                )

            `when`("the session is still collecting") {
                val repo = mockk<StandupRepository>()
                val stager = mockk<OutboundMessageStager>()
                val eventPublisher = mockk<EventPublisher>(relaxed = true)
                val sessionUid = UUID.randomUUID()
                val event = eventOf(sessionUid = sessionUid, notice = notice)
                every {
                    repo.recordAnswer(
                        sessionUid = sessionUid,
                        userId = "U_STANDUP",
                        responses = listOf("Done", "Next"),
                        submittedAt = now,
                    )
                } returns AnswerRecordResult.RECORDED
                every { stager.stage(message = any(), basicInfo = any()) } returns
                    mockk<SendSlackMessageEvent>(relaxed = true)

                serviceOf(repo = repo, stager = stager, eventPublisher = eventPublisher).recordAnswer(event = event)

                then("the repository receives the submitted responses with the service clock timestamp") {
                    verify(exactly = 1) {
                        repo.recordAnswer(
                            sessionUid = sessionUid,
                            userId = "U_STANDUP",
                            responses = listOf("Done", "Next"),
                            submittedAt = now,
                        )
                    }
                }

                then("the DM notice is collapsed to \"Standup submitted.\" only after the answer was stored") {
                    verify(exactly = 1) {
                        stager.stage(
                            message = updateTo(text = SUBMITTED_NOTICE),
                            basicInfo =
                                CommandBasicInfo.forOutbound(
                                    publisherId = "U_STANDUP",
                                    channel = "D_NOTICE",
                                    idempotencyKey = event.idempotencyKey,
                                ),
                        )
                    }
                    verify(exactly = 1) { eventPublisher.publishEvent(events = any()) }
                }
            }

            `when`("the session already closed (T19)") {
                val repo = mockk<StandupRepository>()
                val stager = mockk<OutboundMessageStager>()
                val eventPublisher = mockk<EventPublisher>(relaxed = true)
                every {
                    repo.recordAnswer(
                        sessionUid = any(),
                        userId = any(),
                        responses = any(),
                        submittedAt = any(),
                    )
                } returns
                    AnswerRecordResult.SESSION_CLOSED
                every { stager.stage(message = any(), basicInfo = any()) } returns
                    mockk<SendSlackMessageEvent>(relaxed = true)

                serviceOf(repo = repo, stager = stager, eventPublisher = eventPublisher)
                    .recordAnswer(event = eventOf(sessionUid = UUID.randomUUID(), notice = notice))

                then("the notice says the standup closed and never claims the answer was submitted") {
                    verify(exactly = 1) { stager.stage(message = updateTo(text = CLOSED_NOTICE), basicInfo = any()) }
                    verify(exactly = 0) { stager.stage(message = updateTo(text = SUBMITTED_NOTICE), basicInfo = any()) }
                }
            }

            `when`("the session does not exist") {
                val repo = mockk<StandupRepository>()
                val stager = mockk<OutboundMessageStager>()
                every {
                    repo.recordAnswer(
                        sessionUid = any(),
                        userId = any(),
                        responses = any(),
                        submittedAt = any(),
                    )
                } returns
                    AnswerRecordResult.SESSION_NOT_FOUND
                every { stager.stage(message = any(), basicInfo = any()) } returns
                    mockk<SendSlackMessageEvent>(relaxed = true)

                serviceOf(repo = repo, stager = stager, eventPublisher = mockk(relaxed = true))
                    .recordAnswer(event = eventOf(sessionUid = UUID.randomUUID(), notice = notice))

                then("the prompt is still collapsed, saying the answer was not recorded — never \"submitted\" (G7)") {
                    verify(exactly = 1) {
                        stager.stage(message = updateTo(text = SESSION_NOT_FOUND_NOTICE), basicInfo = any())
                    }
                    verify(exactly = 0) { stager.stage(message = updateTo(text = SUBMITTED_NOTICE), basicInfo = any()) }
                }
            }

            `when`("no notice was ferried with the submission") {
                val repo = mockk<StandupRepository>()
                val stager = mockk<OutboundMessageStager>()
                every {
                    repo.recordAnswer(
                        sessionUid = any(),
                        userId = any(),
                        responses = any(),
                        submittedAt = any(),
                    )
                } returns
                    AnswerRecordResult.RECORDED

                serviceOf(repo = repo, stager = stager, eventPublisher = mockk(relaxed = true))
                    .recordAnswer(event = eventOf(sessionUid = UUID.randomUUID(), notice = null))

                then("the answer is stored and there is no message to update") {
                    verify(exactly = 0) { stager.stage(message = any(), basicInfo = any()) }
                }
            }
        }

        given("onStandupModalOpenFailed") {
            `when`("views.open failed for the standup modal") {
                val stager = mockk<OutboundMessageStager>()
                val eventPublisher = mockk<EventPublisher>(relaxed = true)
                val service =
                    StandupAnswerService(
                        standupRepository = mockk(),
                        outboundStager = stager,
                        eventPublisher = eventPublisher,
                        clock = Clock.fixed(Instant.now(), ZoneOffset.UTC),
                    )
                val idempotencyKey = UUID.randomUUID()
                val event =
                    StandupModalOpenFailedEvent(
                        userId = "U_STANDUP",
                        apiAppId = "A_STANDUP",
                        channel = "D_STANDUP",
                        idempotencyKey = idempotencyKey,
                        reason = "trigger_expired",
                    )
                val ephemeralStub = mockk<SendSlackMessageEvent>(relaxed = true)
                every { stager.stage(message = any(), basicInfo = any()) } returns ephemeralStub
                val queueSlot = slot<DefaultEventQueue<CommandEvent<EventPayload>>>()
                every { eventPublisher.publishEvent(events = capture(queueSlot)) } returns Unit

                service.onStandupModalOpenFailed(event = event)

                then("an ephemeral retry notice is published to the publisher") {
                    verify(exactly = 1) {
                        stager.stage(
                            message =
                                OutboundMessage.Ephemeral(
                                    target = ConversationTarget(id = "D_STANDUP"),
                                    recipient = null,
                                    content =
                                        MessageContent.Text(
                                            headline = null,
                                            markdown =
                                                "Couldn't open the standup form. _Tip: re-click the " +
                                                    "*Fill in standup* button from the original DM to try again._",
                                        ),
                                ),
                            basicInfo =
                                CommandBasicInfo.forOutbound(
                                    appId = "A_STANDUP",
                                    publisherId = "U_STANDUP",
                                    channel = "D_STANDUP",
                                    idempotencyKey = idempotencyKey,
                                ),
                        )
                    }
                    queueSlot.isCaptured shouldBe true
                    queueSlot.captured.poll() shouldNotBe null
                }
            }
        }
    })
