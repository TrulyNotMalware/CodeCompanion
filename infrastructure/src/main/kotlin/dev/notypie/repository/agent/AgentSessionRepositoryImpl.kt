package dev.notypie.repository.agent

import java.time.LocalDateTime

class AgentSessionRepositoryImpl(
    private val jpaAgentSessionRepository: JpaAgentSessionRepository,
) : AgentSessionRepository {
    override fun findProviderSessionId(sessionKey: String): String? =
        jpaAgentSessionRepository.findBySessionKey(sessionKey = sessionKey)?.providerSessionId

    override fun saveProviderSessionId(sessionKey: String, providerSessionId: String, now: LocalDateTime) {
        jpaAgentSessionRepository.upsertProviderSessionId(
            sessionKey = sessionKey,
            providerSessionId = providerSessionId,
            now = now,
        )
    }
}
