package dev.notypie.repository.agent

import dev.notypie.repository.agent.schema.AgentTurnHistorySchema
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface JpaAgentTurnHistoryRepository : JpaRepository<AgentTurnHistorySchema, Long> {
    @Query(
        """
        SELECT new dev.notypie.repository.agent.AgentTurnOutcomeUsage(
            t.outcome, COUNT(t), COALESCE(SUM(t.inputTokens), 0L), COALESCE(SUM(t.outputTokens), 0L),
            COALESCE(SUM(t.durationMs), 0L)
        )
        FROM agent_turn_history t
        WHERE t.createdAt >= :since
        GROUP BY t.outcome
        """,
    )
    fun countByOutcomeSince(
        @Param("since") since: LocalDateTime,
    ): List<AgentTurnOutcomeUsage>

    @Query(
        """
        SELECT new dev.notypie.repository.agent.RequesterTurnUsage(
            t.requesterId, COUNT(t), COALESCE(SUM(t.inputTokens), 0L), COALESCE(SUM(t.outputTokens), 0L)
        )
        FROM agent_turn_history t
        WHERE t.createdAt >= :since
        GROUP BY t.requesterId
        ORDER BY COUNT(t) DESC, t.requesterId ASC
        """,
    )
    fun topRequestersSince(
        @Param("since") since: LocalDateTime,
        pageable: Pageable,
    ): List<RequesterTurnUsage>
}
