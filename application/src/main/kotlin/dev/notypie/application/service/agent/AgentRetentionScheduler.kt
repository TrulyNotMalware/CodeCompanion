package dev.notypie.application.service.agent

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.service.standup.containFailure
import dev.notypie.repository.agent.AgentAskTurnRepository
import dev.notypie.repository.agent.AgentTurnHistoryRepository
import dev.notypie.repository.mcp.McpToolCallHistoryRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

private val log = KotlinLogging.logger {}

internal const val AGENT_RETENTION_PERIOD_MILLIS = 3_600_000L
internal const val AGENT_RETENTION_FIRST_RUN_MIN_MILLIS = 600_000L
internal const val AGENT_RETENTION_FIRST_RUN_MAX_MILLIS = 1_200_000L
internal const val AGENT_RETENTION_MAX_BATCHES_PER_TICK = 20

@Component
class AgentRetentionScheduler(
    private val askTurnRepository: AgentAskTurnRepository,
    private val agentTurnHistoryRepository: AgentTurnHistoryRepository,
    private val mcpToolCallHistoryRepository: McpToolCallHistoryRepository,
    appConfig: AppConfig,
    private val clock: Clock,
) {
    private val askTurnRetention: Duration = Duration.ofDays(appConfig.agent.retention.askTurnDays)
    private val historyRetention: Duration = Duration.ofDays(appConfig.agent.retention.historyDays)
    private val batchSize: Int = appConfig.agent.retention.batchSize

    @Scheduled(
        fixedDelay = AGENT_RETENTION_PERIOD_MILLIS,
        initialDelayString =
            "\${random.long[$AGENT_RETENTION_FIRST_RUN_MIN_MILLIS,$AGENT_RETENTION_FIRST_RUN_MAX_MILLIS]}",
    )
    fun purge() {
        purgeOnce()
    }

    fun purgeOnce(): Int {
        val now = LocalDateTime.now(clock)
        val askTurns =
            purgeTable(table = "agent_ask_turn", olderThan = now.minus(askTurnRetention)) { olderThan ->
                askTurnRepository.deleteFinishedOlderThan(olderThan = olderThan, limit = batchSize)
            }
        val turnHistory =
            purgeTable(table = "agent_turn_history", olderThan = now.minus(historyRetention)) { olderThan ->
                agentTurnHistoryRepository.deleteOlderThan(olderThan = olderThan, limit = batchSize)
            }
        val toolCallHistory =
            purgeTable(table = "mcp_tool_call_history", olderThan = now.minus(historyRetention)) { olderThan ->
                mcpToolCallHistoryRepository.deleteOlderThan(olderThan = olderThan, limit = batchSize)
            }
        return askTurns + turnHistory + toolCallHistory
    }

    private fun purgeTable(table: String, olderThan: LocalDateTime, deleteBatch: (LocalDateTime) -> Int): Int {
        var total = 0
        containFailure(
            onFailure = { exception ->
                log.error(exception) { "Agent retention purge of $table failed after $total rows" }
            },
        ) {
            for (batch in 1..AGENT_RETENTION_MAX_BATCHES_PER_TICK) {
                val deleted = deleteBatch(olderThan)
                total += deleted
                if (deleted < batchSize) break
            }
        }
        if (total > 0) log.info { "Agent retention purged $total rows of $table older than $olderThan" }
        return total
    }
}
