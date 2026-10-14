package dev.notypie.application.service.standup

import dev.notypie.domain.command.DefaultEventQueue
import dev.notypie.domain.command.createRecordStandupAnswerEvent
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.StandupModalOpenFailedEvent
import dev.notypie.domain.command.intent.StandupAnswerConfirmation
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.MessageRef
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.domain.command.outbound.directFallbackOf
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
            val updateNotice = StandupAnswerConfirmation.UpdateNotice(ref = notice)
            val slashConfirmation = StandupAnswerConfirmation.Ephemeral(channel = ConversationTarget(id = "C_CMD"))

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

            fun ephemeralTo(text: String): OutboundMessage.Ephemeral {
                val content = MessageContent.Text(headline = null, markdown = text)
                return OutboundMessage.Ephemeral(
                    target = ConversationTarget(id = "C_CMD"),
                    recipient = UserRef(id = "U_STANDUP"),
                    content = content,
                    detailType = CommandDetailType.STANDUP_ANSWER_SUBMIT,
                    fallback =
                        directFallbackOf(
                            channel = ConversationTarget(id = "C_CMD"),
                            recipient = UserRef(id = "U_STANDUP"),
                            content = content,
                        ),
                )
            }

            fun repoAnswering(result: AnswerRecordResult): StandupRepository {
                val repo = mockk<StandupRepository>()
                every {
                    repo.recordAnswer(sessionUid = any(), userId = any(), responses = any(), submittedAt = any())
                } returns result
                return repo
            }

            fun stubbedStager(): OutboundMessageStager {
                val stager = mockk<OutboundMessageStager>()
                every { stager.stage(message = any(), basicInfo = any()) } returns
                    mockk<SendSlackMessageEvent>(relaxed = true)
                return stager
            }

            `when`("the session is still collecting") {
                val repo = repoAnswering(result = AnswerRecordResult.RECORDED)
                val stager = stubbedStager()
                val eventPublisher = mockk<EventPublisher>(relaxed = true)
                val event = createRecordStandupAnswerEvent(userId = "U_STANDUP", confirmation = updateNotice)

                serviceOf(repo = repo, stager = stager, eventPublisher = eventPublisher).recordAnswer(event = event)

                then("the repository receives the submitted responses with the service clock timestamp") {
                    verify(exactly = 1) {
                        repo.recordAnswer(
                            sessionUid = event.payload.sessionUid,
                            userId = "U_STANDUP",
                            responses = listOf("Done", "Next"),
                            submittedAt = now,
                        )
                    }
                }

                then("the DM notice is collapsed to the submitted text only after the answer was stored") {
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

            `when`("the session already closed") {
                val stager = stubbedStager()

                serviceOf(
                    repo = repoAnswering(result = AnswerRecordResult.SESSION_CLOSED),
                    stager = stager,
                    eventPublisher = mockk(relaxed = true),
                ).recordAnswer(event = createRecordStandupAnswerEvent(confirmation = updateNotice))

                then("the notice says the standup closed and never claims the answer was submitted") {
                    verify(exactly = 1) { stager.stage(message = updateTo(text = CLOSED_NOTICE), basicInfo = any()) }
                    verify(exactly = 0) { stager.stage(message = updateTo(text = SUBMITTED_NOTICE), basicInfo = any()) }
                }
            }

            `when`("the session does not exist") {
                val stager = stubbedStager()

                serviceOf(
                    repo = repoAnswering(result = AnswerRecordResult.SESSION_NOT_FOUND),
                    stager = stager,
                    eventPublisher = mockk(relaxed = true),
                ).recordAnswer(event = createRecordStandupAnswerEvent(confirmation = updateNotice))

                then("the prompt is still collapsed, saying the answer was not recorded") {
                    verify(exactly = 1) {
                        stager.stage(message = updateTo(text = SESSION_NOT_FOUND_NOTICE), basicInfo = any())
                    }
                    verify(exactly = 0) { stager.stage(message = updateTo(text = SUBMITTED_NOTICE), basicInfo = any()) }
                }
            }

            `when`("a /standup answer submission with no message to update is recorded") {
                val stager = stubbedStager()
                val eventPublisher = mockk<EventPublisher>(relaxed = true)
                val event = createRecordStandupAnswerEvent(userId = "U_STANDUP", confirmation = slashConfirmation)

                serviceOf(
                    repo = repoAnswering(result = AnswerRecordResult.RECORDED),
                    stager = stager,
                    eventPublisher = eventPublisher,
                ).recordAnswer(event = event)

                then("the requester gets an ephemeral confirmation in the command channel and no update is staged") {
                    verify(exactly = 1) {
                        stager.stage(
                            message = ephemeralTo(text = SUBMITTED_NOTICE),
                            basicInfo =
                                CommandBasicInfo.forOutbound(
                                    publisherId = "U_STANDUP",
                                    channel = "C_CMD",
                                    idempotencyKey = event.idempotencyKey,
                                ),
                        )
                    }
                    verify(exactly = 0) {
                        stager.stage(message = ofType<OutboundMessage.UpdateMessage>(), basicInfo = any())
                    }
                    verify(exactly = 1) { eventPublisher.publishEvent(events = any()) }
                }
            }

            `when`("a /standup answer submission arrives after the session closed") {
                val stager = stubbedStager()

                serviceOf(
                    repo = repoAnswering(result = AnswerRecordResult.SESSION_CLOSED),
                    stager = stager,
                    eventPublisher = mockk(relaxed = true),
                ).recordAnswer(
                    event = createRecordStandupAnswerEvent(userId = "U_STANDUP", confirmation = slashConfirmation),
                )

                then("the ephemeral says the standup closed and never claims the answer was submitted") {
                    verify(exactly = 1) { stager.stage(message = ephemeralTo(text = CLOSED_NOTICE), basicInfo = any()) }
                    verify(exactly = 1) { stager.stage(message = any(), basicInfo = any()) }
                }
            }

            `when`("a /standup answer submission names a session that no longer exists") {
                val stager = stubbedStager()

                serviceOf(
                    repo = repoAnswering(result = AnswerRecordResult.SESSION_NOT_FOUND),
                    stager = stager,
                    eventPublisher = mockk(relaxed = true),
                ).recordAnswer(
                    event = createRecordStandupAnswerEvent(userId = "U_STANDUP", confirmation = slashConfirmation),
                )

                then("the ephemeral says the answer was not recorded and is the only message staged") {
                    verify(exactly = 1) {
                        stager.stage(message = ephemeralTo(text = SESSION_NOT_FOUND_NOTICE), basicInfo = any())
                    }
                    verify(exactly = 1) { stager.stage(message = any(), basicInfo = any()) }
                }
            }

            `when`("the submission asks for no confirmation") {
                val stager = mockk<OutboundMessageStager>()

                serviceOf(
                    repo = repoAnswering(result = AnswerRecordResult.RECORDED),
                    stager = stager,
                    eventPublisher = mockk(relaxed = true),
                ).recordAnswer(event = createRecordStandupAnswerEvent(confirmation = StandupAnswerConfirmation.None))

                then("the answer is stored and there is no message to send") {
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

                then("an ephemeral retry notice is published to the publisher, with a DM copy for a refusal") {
                    val content =
                        MessageContent.Text(
                            headline = null,
                            markdown =
                                "Couldn't open the standup form. _Tip: re-click the " +
                                    "*Fill in standup* button in the original DM, or run " +
                                    "`/standup answer` in the routine's channel, to try again._",
                        )
                    verify(exactly = 1) {
                        stager.stage(
                            message =
                                OutboundMessage.Ephemeral(
                                    target = ConversationTarget(id = "D_STANDUP"),
                                    recipient = null,
                                    content = content,
                                    fallback =
                                        directFallbackOf(
                                            channel = ConversationTarget(id = "D_STANDUP"),
                                            recipient = UserRef(id = "U_STANDUP"),
                                            content = content,
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
