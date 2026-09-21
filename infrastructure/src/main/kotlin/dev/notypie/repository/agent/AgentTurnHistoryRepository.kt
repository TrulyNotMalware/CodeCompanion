package dev.notypie.repository.agent

import dev.notypie.repository.agent.schema.AgentTurnOutcome
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

interface AgentTurnHistoryRepository {
    fun record(turn: AgentTurnRecord)
}
