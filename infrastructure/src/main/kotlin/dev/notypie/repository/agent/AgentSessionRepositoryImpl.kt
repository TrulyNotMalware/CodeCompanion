package dev.notypie.repository.agent

import dev.notypie.repository.agent.schema.AgentSessionSchema

class AgentSessionRepositoryImpl(
    private val jpaAgentSessionRepository: JpaAgentSessionRepository,
) : AgentSessionRepository {
    override fun findProviderSessionId(sessionKey: String): String? =
        jpaAgentSessionRepository.findBySessionKey(sessionKey = sessionKey)?.providerSessionId

    override fun saveProviderSessionId(sessionKey: String, providerSessionId: String) {
        val existing = jpaAgentSessionRepository.findBySessionKey(sessionKey = sessionKey)
        if (existing == null) {
            jpaAgentSessionRepository.save(
                AgentSessionSchema(
                    sessionKey = sessionKey,
                    providerSessionId = providerSessionId,
                ),
            )
        } else if (existing.providerSessionId != providerSessionId) {
            existing.providerSessionId = providerSessionId
            jpaAgentSessionRepository.save(existing)
        }
    }
}
