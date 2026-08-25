package dev.notypie.repository.agent

import dev.notypie.repository.agent.schema.AgentSessionSchema
import org.springframework.data.jpa.repository.JpaRepository

interface JpaAgentSessionRepository : JpaRepository<AgentSessionSchema, Long> {
    fun findBySessionKey(sessionKey: String): AgentSessionSchema?
}
