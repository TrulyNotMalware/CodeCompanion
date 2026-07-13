package dev.notypie.application.service.agent

import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.security.mcp.ScopedTurnTokenCodec
import dev.notypie.domain.TEST_CHANNEL_NAME
import dev.notypie.domain.TEST_THREAD_TS
import dev.notypie.domain.TEST_USER_NAME
import dev.notypie.domain.command.createAgentConverseRequestEvent
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.agent.AgentGateway
import dev.notypie.impl.agent.AgentTurnRequest
import dev.notypie.impl.agent.AgentTurnResult
import dev.notypie.repository.agent.AgentSessionRepository
import dev.notypie.repository.agent.AgentTurnHistoryRepository
import dev.notypie.repository.agent.AgentTurnRecord
import dev.notypie.repository.agent.schema.AgentTurnOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.time.Duration
import java.time.LocalDateTime

class AgentConverseServiceTest :
    BehaviorSpec({

        val fixedNow = LocalDateTime.of(2026, 7, 3, 12, 0, 0)

        fun stubTransactionManager(): PlatformTransactionManager {
            val tm = mockk<PlatformTransactionManager>()
            val status = mockk<TransactionStatus>(relaxed = true)
            every { tm.getTransaction(any()) } returns status
            every { tm.commit(any()) } just Runs
            every { tm.rollback(any()) } just Runs
            return tm
        }

        fun buildService(
            agentGateway: AgentGateway,
            agentSessionRepository: AgentSessionRepository = mockk(relaxed = true),
            agentTurnHistoryRepository: AgentTurnHistoryRepository = mockk(relaxed = true),
            outboundStager: OutboundMessageStager = mockk(),
            eventPublisher: EventPublisher = mockk(relaxed = true),
            meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry(),
            scopedTurnTokenCodec: ScopedTurnTokenCodec? = null,
        ) = AgentConverseService(
            agentGateway = agentGateway,
            agentSessionRepository = agentSessionRepository,
            agentTurnHistoryRepository = agentTurnHistoryRepository,
            outboundStager = outboundStager,
            eventPublisher = eventPublisher,
            meterRegistry = meterRegistry,
            transactionManager = stubTransactionManager(),
            clock = createFixedUtcClock(now = fixedNow),
            scopedTurnTokenCodec = scopedTurnTokenCodec,
        )

        val stubStagedEvent = mockk<CommandEvent<EventPayload>>(relaxed = true)

        fun stagerCapturing(stagedMessage: CapturingSlot<OutboundMessage>): OutboundMessageStager {
            val stager = mockk<OutboundMessageStager>()
            every { stager.stage(message = capture(stagedMessage), basicInfo = any()) } returns stubStagedEvent
            return stager
        }

        given("a turn that completes") {
            val basicInfo = createCommandBasicInfo()
            val event =
                createAgentConverseRequestEvent(
                    prompt = "what is on my calendar",
                    threadId = TEST_THREAD_TS,
                    responseBasicInfo = basicInfo,
                )
            val expectedSessionKey = "${basicInfo.channel}:$TEST_THREAD_TS"

            val sessionRepository = mockk<AgentSessionRepository>(relaxed = true)
            every { sessionRepository.findProviderSessionId(sessionKey = expectedSessionKey) } returns "sess-prev"

            val historyRepository = mockk<AgentTurnHistoryRepository>(relaxed = true)
            val recordedTurn = slot<AgentTurnRecord>()
            every { historyRepository.record(turn = capture(recordedTurn)) } just Runs

            val gateway = mockk<AgentGateway>()
            val turnRequest = slot<AgentTurnRequest>()
            every { gateway.converse(request = capture(turnRequest)) } returns
                AgentTurnResult.Completed(
                    sessionId = "sess-next",
                    finalText = "You have two meetings.",
                    inputTokens = 120L,
                    outputTokens = 45L,
                )

            val stagedMessage = slot<OutboundMessage>()
            val outboundStager = stagerCapturing(stagedMessage = stagedMessage)

            val eventPublisher = mockk<EventPublisher>(relaxed = true)
            val meterRegistry = SimpleMeterRegistry()
            val service =
                buildService(
                    agentGateway = gateway,
                    agentSessionRepository = sessionRepository,
                    agentTurnHistoryRepository = historyRepository,
                    outboundStager = outboundStager,
                    eventPublisher = eventPublisher,
                    meterRegistry = meterRegistry,
                )

            `when`("handleAgentConverse") {
                service.handleAgentConverse(event = event)

                then("the turn is keyed by channel:thread and resumes the stored session") {
                    turnRequest.captured.sessionKey shouldBe expectedSessionKey
                    turnRequest.captured.prompt shouldBe "what is on my calendar"
                    turnRequest.captured.sessionId shouldBe "sess-prev"
                    turnRequest.captured.userId shouldBe basicInfo.publisherId
                }

                then("no MCP token rides the turn while MCP is disabled") {
                    turnRequest.captured.scopedToken shouldBe null
                }

                then("the per-request context block carries requester, channel, date, and mrkdwn rules") {
                    val contextPrompt = turnRequest.captured.appendSystemPrompt.orEmpty()
                    contextPrompt shouldContain "<@${basicInfo.publisherId}> ($TEST_USER_NAME)"
                    contextPrompt shouldContain "#$TEST_CHANNEL_NAME"
                    contextPrompt shouldContain "2026-07-03"
                    contextPrompt shouldContain "mrkdwn"
                }

                then("the new provider session id is stored for the next turn") {
                    verify(exactly = 1) {
                        sessionRepository.saveProviderSessionId(
                            sessionKey = expectedSessionKey,
                            providerSessionId = "sess-next",
                        )
                    }
                }

                then("the answer is staged transport-neutral into the originating thread and published") {
                    val staged = stagedMessage.captured.shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                    staged.target.id shouldBe basicInfo.channel
                    staged.detailType shouldBe CommandDetailType.AGENT_CONVERSE
                    staged.threadId shouldBe TEST_THREAD_TS
                    val content = staged.content.shouldBeInstanceOf<MessageContent.Text>()
                    content.headline shouldBe AgentConverseService.RESPONSE_HEADLINE
                    content.markdown shouldBe "You have two meetings."
                    verify(exactly = 1) { eventPublisher.publishEvent(events = any()) }
                }

                then("the turn is audited with outcome and token usage") {
                    recordedTurn.captured.sessionKey shouldBe expectedSessionKey
                    recordedTurn.captured.requesterId shouldBe basicInfo.publisherId
                    recordedTurn.captured.outcome shouldBe AgentTurnOutcome.COMPLETED
                    recordedTurn.captured.inputTokens shouldBe 120L
                    recordedTurn.captured.outputTokens shouldBe 45L
                }

                then("turn and token metrics are recorded") {
                    meterRegistry
                        .counter(AgentConverseService.METRIC_TURNS, "outcome", "completed")
                        .count() shouldBe 1.0
                    meterRegistry
                        .counter(AgentConverseService.METRIC_TOKENS, "direction", "input")
                        .count() shouldBe 120.0
                    meterRegistry
                        .counter(AgentConverseService.METRIC_TOKENS, "direction", "output")
                        .count() shouldBe 45.0
                }
            }
        }

        given("a turn that completes with an empty final text") {
            val gateway = mockk<AgentGateway>()
            every { gateway.converse(request = any()) } returns
                AgentTurnResult.Completed(sessionId = null, finalText = "")

            val stagedMessage = slot<OutboundMessage>()
            val outboundStager = stagerCapturing(stagedMessage = stagedMessage)

            val sessionRepository = mockk<AgentSessionRepository>(relaxed = true)
            every { sessionRepository.findProviderSessionId(sessionKey = any()) } returns null
            val service =
                buildService(
                    agentGateway = gateway,
                    agentSessionRepository = sessionRepository,
                    outboundStager = outboundStager,
                )

            `when`("handleAgentConverse") {
                service.handleAgentConverse(event = createAgentConverseRequestEvent())

                then("a placeholder is posted instead of an empty Slack message") {
                    val staged = stagedMessage.captured.shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                    val content = staged.content.shouldBeInstanceOf<MessageContent.Text>()
                    content.markdown shouldBe AgentConverseService.EMPTY_RESPONSE_MESSAGE
                }

                then("no session id is stored when the backend returned none") {
                    verify(exactly = 0) {
                        sessionRepository.saveProviderSessionId(sessionKey = any(), providerSessionId = any())
                    }
                }
            }
        }

        given("a turn rejected as busy") {
            val basicInfo = createCommandBasicInfo()
            val gateway = mockk<AgentGateway>()
            every { gateway.converse(request = any()) } returns AgentTurnResult.Busy

            val stagedMessage = slot<OutboundMessage>()
            val outboundStager = stagerCapturing(stagedMessage = stagedMessage)

            val sessionRepository = mockk<AgentSessionRepository>(relaxed = true)
            every { sessionRepository.findProviderSessionId(sessionKey = any()) } returns null
            val historyRepository = mockk<AgentTurnHistoryRepository>(relaxed = true)
            val recordedTurn = slot<AgentTurnRecord>()
            every { historyRepository.record(turn = capture(recordedTurn)) } just Runs
            val eventPublisher = mockk<EventPublisher>(relaxed = true)
            val service =
                buildService(
                    agentGateway = gateway,
                    agentSessionRepository = sessionRepository,
                    agentTurnHistoryRepository = historyRepository,
                    outboundStager = outboundStager,
                    eventPublisher = eventPublisher,
                )

            `when`("handleAgentConverse") {
                service.handleAgentConverse(
                    event = createAgentConverseRequestEvent(responseBasicInfo = basicInfo),
                )

                then("the requester gets the busy ephemeral") {
                    val staged = stagedMessage.captured.shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                    staged.target.id shouldBe basicInfo.channel
                    staged.recipient?.id shouldBe basicInfo.publisherId
                    staged.detailType shouldBe CommandDetailType.AGENT_CONVERSE
                    val content = staged.content.shouldBeInstanceOf<MessageContent.Text>()
                    content.markdown shouldBe AgentConverseService.BUSY_MESSAGE
                    verify(exactly = 1) { eventPublisher.publishEvent(events = any()) }
                }

                then("the turn is audited as BUSY") {
                    recordedTurn.captured.outcome shouldBe AgentTurnOutcome.BUSY
                }
            }
        }

        given("a turn that fails") {
            val gateway = mockk<AgentGateway>()
            every { gateway.converse(request = any()) } returns
                AgentTurnResult.Failed(code = "timeout", message = "turn exceeded the ceiling")

            val stagedMessage = slot<OutboundMessage>()
            val outboundStager = stagerCapturing(stagedMessage = stagedMessage)

            val sessionRepository = mockk<AgentSessionRepository>(relaxed = true)
            every { sessionRepository.findProviderSessionId(sessionKey = any()) } returns null
            val historyRepository = mockk<AgentTurnHistoryRepository>(relaxed = true)
            val recordedTurn = slot<AgentTurnRecord>()
            every { historyRepository.record(turn = capture(recordedTurn)) } just Runs
            val service =
                buildService(
                    agentGateway = gateway,
                    agentSessionRepository = sessionRepository,
                    agentTurnHistoryRepository = historyRepository,
                    outboundStager = outboundStager,
                )

            `when`("handleAgentConverse") {
                service.handleAgentConverse(event = createAgentConverseRequestEvent())

                then("a friendly failure message is posted into the thread") {
                    val staged = stagedMessage.captured.shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                    val content = staged.content.shouldBeInstanceOf<MessageContent.Text>()
                    content.markdown shouldBe AgentConverseService.FAILURE_MESSAGE
                }

                then("the turn is audited as FAILED with the sidecar error code") {
                    recordedTurn.captured.outcome shouldBe AgentTurnOutcome.FAILED
                    recordedTurn.captured.errorCode shouldBe "timeout"
                }
            }
        }

        given("an event without a thread anchor") {
            val basicInfo = createCommandBasicInfo()
            val gateway = mockk<AgentGateway>()
            val turnRequest = slot<AgentTurnRequest>()
            every { gateway.converse(request = capture(turnRequest)) } returns
                AgentTurnResult.Completed(sessionId = null, finalText = "hi")

            val stagedMessage = slot<OutboundMessage>()
            val outboundStager = stagerCapturing(stagedMessage = stagedMessage)

            val sessionRepository = mockk<AgentSessionRepository>(relaxed = true)
            every { sessionRepository.findProviderSessionId(sessionKey = any()) } returns null
            val service =
                buildService(
                    agentGateway = gateway,
                    agentSessionRepository = sessionRepository,
                    outboundStager = outboundStager,
                )

            `when`("handleAgentConverse") {
                service.handleAgentConverse(
                    event = createAgentConverseRequestEvent(threadId = null, responseBasicInfo = basicInfo),
                )

                then("the session falls back to a per-user channel key and the reply is un-threaded") {
                    turnRequest.captured.sessionKey shouldBe "${basicInfo.channel}:${basicInfo.publisherId}"
                    val staged = stagedMessage.captured.shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                    staged.threadId shouldBe null
                }
            }
        }

        given("MCP enabled via a wired token codec") {
            val basicInfo = createCommandBasicInfo()
            val event =
                createAgentConverseRequestEvent(
                    prompt = "what's the outbox status?",
                    threadId = TEST_THREAD_TS,
                    responseBasicInfo = basicInfo,
                )
            val codec =
                ScopedTurnTokenCodec(
                    signingSecret = "test-signing-secret",
                    tokenTtl = Duration.ofSeconds(300L),
                    clockSkew = Duration.ofSeconds(30L),
                )

            val gateway = mockk<AgentGateway>()
            val turnRequest = slot<AgentTurnRequest>()
            every { gateway.converse(request = capture(turnRequest)) } returns
                AgentTurnResult.Completed(sessionId = null, finalText = "done")

            val stagedMessage = slot<OutboundMessage>()
            val service =
                buildService(
                    agentGateway = gateway,
                    outboundStager = stagerCapturing(stagedMessage = stagedMessage),
                    scopedTurnTokenCodec = codec,
                )

            `when`("handleAgentConverse") {
                service.handleAgentConverse(event = event)

                then("the turn carries a token the codec verifies back to this turn's identity") {
                    val scopedToken = turnRequest.captured.scopedToken.shouldNotBeNull()
                    val decoded = codec.verify(token = scopedToken).shouldNotBeNull()
                    decoded.userId shouldBe basicInfo.publisherId
                    decoded.sessionKey shouldBe "${basicInfo.channel}:$TEST_THREAD_TS"
                    decoded.turnId shouldBe event.idempotencyKey.toString()
                }
            }
        }
    })
