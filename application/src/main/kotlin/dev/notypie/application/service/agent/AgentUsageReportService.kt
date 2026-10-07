package dev.notypie.application.service.agent

import dev.notypie.application.common.detachedTemplate
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.AgentUsageReportRequestEvent
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.publishOne
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.repository.agent.AgentTurnHistoryRepository
import dev.notypie.repository.agent.AgentTurnOutcomeUsage
import dev.notypie.repository.agent.RequesterTurnUsage
import dev.notypie.repository.mcp.McpToolCallHistoryRepository
import dev.notypie.repository.mcp.ToolCallUsage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val log = KotlinLogging.logger {}

private const val TOP_REQUESTER_LIMIT = 5
private val USAGE_SINCE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun Long.withSeparators(): String = "%,d".format(Locale.ENGLISH, this)

@Service
class AgentUsageReportService(
    private val agentTurnHistoryRepository: AgentTurnHistoryRepository,
    private val mcpToolCallHistoryRepository: McpToolCallHistoryRepository,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val reportTemplate: TransactionTemplate = detachedTemplate(transactionManager = transactionManager)

    @EventListener
    fun handleUsageReport(event: AgentUsageReportRequestEvent) {
        val payload = event.payload
        val text =
            try {
                checkNotNull(reportTemplate.execute { renderReport(days = payload.days) })
            } catch (exception: Exception) {
                log.error(exception) {
                    "Failed to render AI usage report idempotencyKey=${event.idempotencyKey}"
                }
                "Failed to read AI usage. Check application logs."
            }

        outboundStager
            .stage(
                message =
                    OutboundMessage.Ephemeral(
                        target = ConversationTarget(id = payload.responseBasicInfo.channel),
                        recipient = UserRef(id = payload.responseBasicInfo.publisherId),
                        content =
                            MessageContent.Text(
                                headline = "CodeCompanion — AI usage",
                                markdown = text,
                            ),
                        detailType = CommandDetailType.AGENT_USAGE_REPORT,
                    ),
                basicInfo = payload.responseBasicInfo,
            )?.let { eventPublisher.publishOne(event = it) }
    }

    internal fun renderReport(days: Int): String {
        val since = LocalDateTime.now(clock).minusDays(days.toLong())
        val outcomes = agentTurnHistoryRepository.countByOutcomeSince(since = since).sortedBy { it.outcome.ordinal }
        val requesters = agentTurnHistoryRepository.topRequestersSince(since = since, limit = TOP_REQUESTER_LIMIT)
        val toolCalls = mcpToolCallHistoryRepository.countByToolSince(since = since)
        val turns = outcomes.sumOf { it.turns }
        if (turns == 0L && toolCalls.isEmpty()) return "No AI turns or tool calls in the last $days day(s)."

        return buildList {
            add("*AI usage — last $days day(s)* (since ${since.format(USAGE_SINCE_FORMAT)})")
            add(turnsLine(outcomes = outcomes, turns = turns))
            add(
                "• Tokens: ${outcomes.sumOf { it.inputTokens }.withSeparators()} in / " +
                    "${outcomes.sumOf { it.outputTokens }.withSeparators()} out",
            )
            if (turns > 0L) {
                val averageSeconds = outcomes.sumOf { it.totalDurationMs }.toDouble() / turns / 1_000
                add("• Avg duration: ${"%.1f".format(Locale.ENGLISH, averageSeconds)} s")
            }
            if (requesters.isNotEmpty()) add(requestersLine(requesters = requesters))
            if (toolCalls.isNotEmpty()) add(toolsLine(toolCalls = toolCalls))
        }.joinToString(separator = "\n")
    }

    private fun turnsLine(outcomes: List<AgentTurnOutcomeUsage>, turns: Long): String {
        if (outcomes.isEmpty()) return "• Turns: 0"
        val breakdown =
            outcomes.joinToString(separator = ", ") { "${it.outcome.name.lowercase()} ${it.turns.withSeparators()}" }
        return "• Turns: ${turns.withSeparators()} ($breakdown)"
    }

    private fun requestersLine(requesters: List<RequesterTurnUsage>): String =
        "• Top requesters: " +
            requesters.joinToString(separator = "; ") {
                "<@${it.requesterId}> ${it.turns.withSeparators()} turn(s), " +
                    "${it.inputTokens.withSeparators()} in / ${it.outputTokens.withSeparators()} out"
            }

    private fun toolsLine(toolCalls: List<ToolCallUsage>): String =
        "• MCP tools: " +
            toolCalls.groupBy { it.toolName }.entries.joinToString(separator = "; ") { (toolName, calls) ->
                "$toolName " +
                    calls.joinToString(separator = ", ") {
                        "${it.calls.withSeparators()} ${it.outcome.name.lowercase()}"
                    }
            }
}
