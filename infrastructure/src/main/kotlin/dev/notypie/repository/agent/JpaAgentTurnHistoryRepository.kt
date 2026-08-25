package dev.notypie.repository.agent

import dev.notypie.repository.agent.schema.AgentTurnHistorySchema
import org.springframework.data.jpa.repository.JpaRepository

interface JpaAgentTurnHistoryRepository : JpaRepository<AgentTurnHistorySchema, Long>
