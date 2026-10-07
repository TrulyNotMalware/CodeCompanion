package dev.notypie.application.service.agent

import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.createAgentUsageReportRequestEvent
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.command.event.createSendSlackMessageEvent
import dev.notypie.repository.agent.AgentTurnHistoryRepository
import dev.notypie.repository.agent.schema.AgentTurnOutcome
import dev.notypie.repository.mcp.McpToolCallHistoryRepository
import dev.notypie.repository.mcp.schema.McpToolCallOutcome
import dev.notypie.schema.createAgentTurnOutcomeUsage
import dev.notypie.schema.createRequesterTurnUsage
import dev.notypie.schema.createToolCallUsage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDateTime

class AgentUsageReportServiceTest :
    BehaviorSpec({
        val now = LocalDateTime.of(2026, 10, 7, 9, 30, 0)
        val since = now.minusDays(7L)

        fun serviceWith(
            agentTurnHistoryRepository: AgentTurnHistoryRepository,
            mcpToolCallHistoryRepository: McpToolCallHistoryRepository,
            outboundStager: OutboundMessageStager = mockk(),
            eventPublisher: EventPublisher = mockk(relaxed = true),
        ) = AgentUsageReportService(
            agentTurnHistoryRepository = agentTurnHistoryRepository,
            mcpToolCallHistoryRepository = mcpToolCallHistoryRepository,
            outboundStager = outboundStager,
            eventPublisher = eventPublisher,
            clock = createFixedUtcClock(now = now),
        )

        given("renderReport") {
            `when`("there are no turns and no tool calls in the window") {
                val turnHistory = mockk<AgentTurnHistoryRepository>()
                every { turnHistory.countByOutcomeSince(since = since) } returns emptyList()
                every { turnHistory.topRequestersSince(since = since, limit = 5) } returns emptyList()
                val toolHistory = mockk<McpToolCallHistoryRepository>()
                every { toolHistory.countByToolSince(since = since) } returns emptyList()

                then("the empty text names the window") {
                    serviceWith(agentTurnHistoryRepository = turnHistory, mcpToolCallHistoryRepository = toolHistory)
                        .renderReport(days = 7) shouldBe "No AI turns or tool calls in the last 7 day(s)."
                }
            }

            `when`("turns, requesters and tool calls are present") {
                val turnHistory = mockk<AgentTurnHistoryRepository>()
                every { turnHistory.countByOutcomeSince(since = since) } returns
                    listOf(
                        createAgentTurnOutcomeUsage(
                            outcome = AgentTurnOutcome.FAILED,
                            turns = 2L,
                            inputTokens = 0L,
                            outputTokens = 0L,
                            totalDurationMs = 1_000L,
                        ),
                        createAgentTurnOutcomeUsage(
                            outcome = AgentTurnOutcome.COMPLETED,
                            turns = 1_200L,
                            inputTokens = 90_000L,
                            outputTokens = 1_500L,
                            totalDurationMs = 2_759_000L,
                        ),
                        createAgentTurnOutcomeUsage(
                            outcome = AgentTurnOutcome.BUSY,
                            turns = 1L,
                            inputTokens = 0L,
                            outputTokens = 0L,
                            totalDurationMs = 0L,
                        ),
                    )
                every { turnHistory.topRequestersSince(since = since, limit = 5) } returns
                    listOf(
                        createRequesterTurnUsage(
                            requesterId = "U1",
                            turns = 1_100L,
                            inputTokens = 80_000L,
                            outputTokens = 1_000L,
                        ),
                        createRequesterTurnUsage(requesterId = "U2", turns = 3L, inputTokens = 0L, outputTokens = 0L),
                    )
                val toolHistory = mockk<McpToolCallHistoryRepository>()
                every { toolHistory.countByToolSince(since = since) } returns
                    listOf(
                        createToolCallUsage(
                            toolName = "get_status",
                            outcome = McpToolCallOutcome.COMPLETED,
                            calls = 2L,
                        ),
                        createToolCallUsage(toolName = "get_status", outcome = McpToolCallOutcome.DENIED, calls = 1L),
                        createToolCallUsage(
                            toolName = "list_meetings",
                            outcome = McpToolCallOutcome.COMPLETED,
                            calls = 1_234L,
                        ),
                    )

                then("each line is rendered with thousands separators, ordered outcomes and lowercased names") {
                    serviceWith(agentTurnHistoryRepository = turnHistory, mcpToolCallHistoryRepository = toolHistory)
                        .renderReport(days = 7) shouldBe
                        listOf(
                            "*AI usage — last 7 day(s)* (since 2026-09-30 09:30)",
                            "• Turns: 1,203 (completed 1,200, busy 1, failed 2)",
                            "• Tokens: 90,000 in / 1,500 out",
                            "• Avg duration: 2.3 s",
                            "• Top requesters: <@U1> 1,100 turn(s), 80,000 in / 1,000 out; " +
                                "<@U2> 3 turn(s), 0 in / 0 out",
                            "• MCP tools: get_status 2 completed, 1 denied; list_meetings 1,234 completed",
                        ).joinToString(separator = "\n")
                }
            }

            `when`("only tool calls are present in the window") {
                val turnHistory = mockk<AgentTurnHistoryRepository>()
                every { turnHistory.countByOutcomeSince(since = since) } returns emptyList()
                every { turnHistory.topRequestersSince(since = since, limit = 5) } returns emptyList()
                val toolHistory = mockk<McpToolCallHistoryRepository>()
                every { toolHistory.countByToolSince(since = since) } returns
                    listOf(createToolCallUsage(toolName = "get_status", outcome = McpToolCallOutcome.FAILED))

                then("zero turns are reported without an average or a requester line") {
                    serviceWith(agentTurnHistoryRepository = turnHistory, mcpToolCallHistoryRepository = toolHistory)
                        .renderReport(days = 7) shouldBe
                        listOf(
                            "*AI usage — last 7 day(s)* (since 2026-09-30 09:30)",
                            "• Turns: 0",
                            "• Tokens: 0 in / 0 out",
                            "• MCP tools: get_status 1 failed",
                        ).joinToString(separator = "\n")
                }
            }
        }

        given("handleUsageReport") {
            val event = createAgentUsageReportRequestEvent(days = 7)
            val basic = event.payload.responseBasicInfo
            val outboundStub =
                createSendSlackMessageEvent(
                    commandDetailType = CommandDetailType.AGENT_USAGE_REPORT,
                    idempotencyKey = basic.idempotencyKey,
                )

            `when`("the report renders") {
                val turnHistory = mockk<AgentTurnHistoryRepository>()
                every { turnHistory.countByOutcomeSince(since = since) } returns emptyList()
                every { turnHistory.topRequestersSince(since = since, limit = 5) } returns emptyList()
                val toolHistory = mockk<McpToolCallHistoryRepository>()
                every { toolHistory.countByToolSince(since = since) } returns emptyList()
                val stager = mockk<OutboundMessageStager>()
                val captured = slot<OutboundMessage>()
                every { stager.stage(message = capture(captured), basicInfo = basic) } returns outboundStub
                val eventPublisher = mockk<EventPublisher>()
                val publishedQueue = slot<EventQueue<CommandEvent<EventPayload>>>()
                every { eventPublisher.publishEvent(events = capture(publishedQueue)) } returns Unit

                serviceWith(
                    agentTurnHistoryRepository = turnHistory,
                    mcpToolCallHistoryRepository = toolHistory,
                    outboundStager = stager,
                    eventPublisher = eventPublisher,
                ).handleUsageReport(event = event)

                then("one channel message with the usage headline and detail type is staged and published") {
                    val message = captured.captured.shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                    message.target shouldBe ConversationTarget(id = basic.channel)
                    message.detailType shouldBe CommandDetailType.AGENT_USAGE_REPORT
                    message.content shouldBe
                        MessageContent.Text(
                            headline = "CodeCompanion — AI usage",
                            markdown = "No AI turns or tool calls in the last 7 day(s).",
                        )
                    publishedQueue.captured.toList().single() shouldBe outboundStub
                }
            }

            `when`("the repository throws while reading usage") {
                val turnHistory = mockk<AgentTurnHistoryRepository>()
                every { turnHistory.countByOutcomeSince(since = any()) } throws RuntimeException("db down")
                val stager = mockk<OutboundMessageStager>()
                val captured = slot<OutboundMessage>()
                every { stager.stage(message = capture(captured), basicInfo = any()) } returns outboundStub

                serviceWith(
                    agentTurnHistoryRepository = turnHistory,
                    mcpToolCallHistoryRepository = mockk(),
                    outboundStager = stager,
                ).handleUsageReport(event = event)

                then("the listener still stages a friendly fallback instead of crashing") {
                    captured.captured
                        .shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
                        .content
                        .shouldBeInstanceOf<MessageContent.Text>()
                        .markdown shouldBe "Failed to read AI usage. Check application logs."
                }
            }
        }
    })
