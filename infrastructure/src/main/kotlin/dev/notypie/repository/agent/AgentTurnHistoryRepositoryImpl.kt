package dev.notypie.repository.agent

import dev.notypie.repository.agent.schema.AgentTurnHistorySchema

class AgentTurnHistoryRepositoryImpl(
    private val jpaAgentTurnHistoryRepository: JpaAgentTurnHistoryRepository,
) : AgentTurnHistoryRepository {
    override fun record(turn: AgentTurnRecord) {
        jpaAgentTurnHistoryRepository.save(
            AgentTurnHistorySchema(
                sessionKey = turn.sessionKey,
                requesterId = turn.requesterId,
                channel = turn.channel,
                idempotencyKey = turn.idempotencyKey.toString(),
                outcome = turn.outcome,
                errorCode = turn.errorCode,
                inputTokens = turn.inputTokens,
                outputTokens = turn.outputTokens,
                durationMs = turn.durationMs,
            ),
        )
    }
}
