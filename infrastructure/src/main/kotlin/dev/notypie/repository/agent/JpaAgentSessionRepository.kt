package dev.notypie.repository.agent

import dev.notypie.repository.agent.schema.AgentSessionSchema
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

interface JpaAgentSessionRepository : JpaRepository<AgentSessionSchema, Long> {
    fun findBySessionKey(sessionKey: String): AgentSessionSchema?

    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO agent_session (session_key, provider_session_id, created_at, updated_at)
            VALUES (:sessionKey, :providerSessionId, :now, :now)
            ON DUPLICATE KEY UPDATE provider_session_id = :providerSessionId, updated_at = :now
        """,
        nativeQuery = true,
    )
    fun upsertProviderSessionId(
        @Param("sessionKey") sessionKey: String,
        @Param("providerSessionId") providerSessionId: String,
        @Param("now") now: LocalDateTime,
    ): Int
}
