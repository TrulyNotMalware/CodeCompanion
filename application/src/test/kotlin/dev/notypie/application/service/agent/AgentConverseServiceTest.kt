package dev.notypie.application.service.agent

import dev.notypie.domain.TEST_THREAD_TS
import dev.notypie.domain.command.createAgentConverseRequestEvent
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.impl.agent.AgentGateway
import dev.notypie.impl.agent.AgentTurnRequest
import dev.notypie.impl.agent.AgentTurnResult
import dev.notypie.impl.command.SlackApiEventConstructor
import dev.notypie.impl.command.event.createSendSlackMessageEvent
import dev.notypie.repository.agent.AgentSessionRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.util.UUID

class AgentConverseServiceTest :
    BehaviorSpec({

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
            slackEventBuilder: SlackApiEventConstructor = mockk(),
            eventPublisher: EventPublisher = mockk(relaxed = true),
        ) = AgentConverseService(
            agentGateway = agentGateway,
            agentSessionRepository = agentSessionRepository,
            slackEventBuilder = slackEventBuilder,
            eventPublisher = eventPublisher,
            transactionManager = stubTransactionManager(),
        )

        val stubOutbound =
            createSendSlackMessageEvent(
                commandDetailType = CommandDetailType.AGENT_CONVERSE,
                idempotencyKey = UUID.randomUUID(),
            )

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

            val gateway = mockk<AgentGateway>()
            val turnRequest = slot<AgentTurnRequest>()
            every { gateway.converse(request = capture(turnRequest)) } returns
                AgentTurnResult.Completed(sessionId = "sess-next", finalText = "You have two meetings.")

            val slackEventBuilder = mockk<SlackApiEventConstructor>()
            val renderedText = slot<String>()
            val renderedThreadTs = mutableListOf<String?>()
            every {
                slackEventBuilder.simpleTextRequest(
                    commandDetailType = any(),
                    headLineText = any(),
                    commandBasicInfo = any(),
                    simpleString = capture(renderedText),
                    threadTs = captureNullable(renderedThreadTs),
                )
            } returns stubOutbound

            val eventPublisher = mockk<EventPublisher>(relaxed = true)
            val service =
                buildService(
                    agentGateway = gateway,
                    agentSessionRepository = sessionRepository,
                    slackEventBuilder = slackEventBuilder,
                    eventPublisher = eventPublisher,
                )

            `when`("handleAgentConverse") {
                service.handleAgentConverse(event = event)

                then("the turn is keyed by channel:thread and resumes the stored session") {
                    turnRequest.captured.sessionKey shouldBe expectedSessionKey
                    turnRequest.captured.prompt shouldBe "what is on my calendar"
                    turnRequest.captured.sessionId shouldBe "sess-prev"
                    turnRequest.captured.userId shouldBe basicInfo.publisherId
                }

                then("the new provider session id is stored for the next turn") {
                    verify(exactly = 1) {
                        sessionRepository.saveProviderSessionId(
                            sessionKey = expectedSessionKey,
                            providerSessionId = "sess-next",
                        )
                    }
                }

                then("the answer is rendered into the originating thread and published") {
                    renderedText.captured shouldBe "You have two meetings."
                    renderedThreadTs.single() shouldBe TEST_THREAD_TS
                    verify(exactly = 1) { eventPublisher.publishEvent(events = any()) }
                }
            }
        }

        given("a turn that completes with an empty final text") {
            val gateway = mockk<AgentGateway>()
            every { gateway.converse(request = any()) } returns
                AgentTurnResult.Completed(sessionId = null, finalText = "")

            val slackEventBuilder = mockk<SlackApiEventConstructor>()
            val renderedText = slot<String>()
            every {
                slackEventBuilder.simpleTextRequest(
                    commandDetailType = any(),
                    headLineText = any(),
                    commandBasicInfo = any(),
                    simpleString = capture(renderedText),
                    threadTs = any(),
                )
            } returns stubOutbound

            val sessionRepository = mockk<AgentSessionRepository>(relaxed = true)
            every { sessionRepository.findProviderSessionId(sessionKey = any()) } returns null
            val service =
                buildService(
                    agentGateway = gateway,
                    agentSessionRepository = sessionRepository,
                    slackEventBuilder = slackEventBuilder,
                )

            `when`("handleAgentConverse") {
                service.handleAgentConverse(event = createAgentConverseRequestEvent())

                then("a placeholder is posted instead of an empty Slack message") {
                    renderedText.captured shouldBe AgentConverseService.EMPTY_RESPONSE_MESSAGE
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

            val slackEventBuilder = mockk<SlackApiEventConstructor>()
            val ephemeralText = slot<String>()
            every {
                slackEventBuilder.simpleEphemeralTextRequest(
                    textMessage = capture(ephemeralText),
                    commandBasicInfo = any(),
                    commandDetailType = any(),
                    targetUserId = any(),
                )
            } returns stubOutbound

            val sessionRepository = mockk<AgentSessionRepository>(relaxed = true)
            every { sessionRepository.findProviderSessionId(sessionKey = any()) } returns null
            val eventPublisher = mockk<EventPublisher>(relaxed = true)
            val service =
                buildService(
                    agentGateway = gateway,
                    agentSessionRepository = sessionRepository,
                    slackEventBuilder = slackEventBuilder,
                    eventPublisher = eventPublisher,
                )

            `when`("handleAgentConverse") {
                service.handleAgentConverse(
                    event = createAgentConverseRequestEvent(responseBasicInfo = basicInfo),
                )

                then("the requester gets the busy ephemeral") {
                    ephemeralText.captured shouldBe AgentConverseService.BUSY_MESSAGE
                    verify(exactly = 1) { eventPublisher.publishEvent(events = any()) }
                }
            }
        }

        given("a turn that fails") {
            val gateway = mockk<AgentGateway>()
            every { gateway.converse(request = any()) } returns
                AgentTurnResult.Failed(code = "timeout", message = "turn exceeded the ceiling")

            val slackEventBuilder = mockk<SlackApiEventConstructor>()
            val renderedText = slot<String>()
            every {
                slackEventBuilder.simpleTextRequest(
                    commandDetailType = any(),
                    headLineText = any(),
                    commandBasicInfo = any(),
                    simpleString = capture(renderedText),
                    threadTs = any(),
                )
            } returns stubOutbound

            val sessionRepository = mockk<AgentSessionRepository>(relaxed = true)
            every { sessionRepository.findProviderSessionId(sessionKey = any()) } returns null
            val service =
                buildService(
                    agentGateway = gateway,
                    agentSessionRepository = sessionRepository,
                    slackEventBuilder = slackEventBuilder,
                )

            `when`("handleAgentConverse") {
                service.handleAgentConverse(event = createAgentConverseRequestEvent())

                then("a friendly failure message is posted into the thread") {
                    renderedText.captured shouldBe AgentConverseService.FAILURE_MESSAGE
                }
            }
        }

        given("an event without a thread anchor") {
            val basicInfo = createCommandBasicInfo()
            val gateway = mockk<AgentGateway>()
            val turnRequest = slot<AgentTurnRequest>()
            every { gateway.converse(request = capture(turnRequest)) } returns
                AgentTurnResult.Completed(sessionId = null, finalText = "hi")

            val slackEventBuilder = mockk<SlackApiEventConstructor>()
            val renderedThreadTs = mutableListOf<String?>()
            every {
                slackEventBuilder.simpleTextRequest(
                    commandDetailType = any(),
                    headLineText = any(),
                    commandBasicInfo = any(),
                    simpleString = any(),
                    threadTs = captureNullable(renderedThreadTs),
                )
            } returns stubOutbound

            val sessionRepository = mockk<AgentSessionRepository>(relaxed = true)
            every { sessionRepository.findProviderSessionId(sessionKey = any()) } returns null
            val service =
                buildService(
                    agentGateway = gateway,
                    agentSessionRepository = sessionRepository,
                    slackEventBuilder = slackEventBuilder,
                )

            `when`("handleAgentConverse") {
                service.handleAgentConverse(
                    event = createAgentConverseRequestEvent(threadId = null, responseBasicInfo = basicInfo),
                )

                then("the session falls back to a per-user channel key and the reply is un-threaded") {
                    turnRequest.captured.sessionKey shouldBe "${basicInfo.channel}:${basicInfo.publisherId}"
                    renderedThreadTs.single() shouldBe null
                }
            }
        }
    })
