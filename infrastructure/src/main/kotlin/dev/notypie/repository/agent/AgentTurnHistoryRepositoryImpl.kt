package dev.notypie.repository.agent

import dev.notypie.repository.agent.schema.AgentTurnHistorySchema
import org.springframework.data.domain.PageRequest
import java.time.LocalDateTime

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

    override fun countByOutcomeSince(since: LocalDateTime): List<AgentTurnOutcomeUsage> =
        jpaAgentTurnHistoryRepository.countByOutcomeSince(since = since)

    override fun topRequestersSince(since: LocalDateTime, limit: Int): List<RequesterTurnUsage> =
        jpaAgentTurnHistoryRepository.topRequestersSince(since = since, pageable = PageRequest.of(0, limit))
}
