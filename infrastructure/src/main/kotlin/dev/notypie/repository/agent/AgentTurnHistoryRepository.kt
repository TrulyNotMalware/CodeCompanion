package dev.notypie.repository.agent

import dev.notypie.repository.agent.schema.AgentTurnOutcome
import java.time.LocalDateTime
import java.util.UUID

data class AgentTurnRecord(
    val sessionKey: String,
    val requesterId: String,
    val channel: String,
    val idempotencyKey: UUID,
    val outcome: AgentTurnOutcome,
    val errorCode: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val durationMs: Long,
)

data class AgentTurnOutcomeUsage(
    val outcome: AgentTurnOutcome,
    val turns: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalDurationMs: Long,
)

data class RequesterTurnUsage(
    val requesterId: String,
    val turns: Long,
    val inputTokens: Long,
    val outputTokens: Long,
)

interface AgentTurnHistoryRepository {
    fun record(turn: AgentTurnRecord)

    fun countByOutcomeSince(since: LocalDateTime): List<AgentTurnOutcomeUsage>

    fun topRequestersSince(since: LocalDateTime, limit: Int): List<RequesterTurnUsage>
}
