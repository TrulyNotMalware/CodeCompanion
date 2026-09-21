package dev.notypie.repository.agent

interface AgentSessionRepository {
    fun findProviderSessionId(sessionKey: String): String?

    fun saveProviderSessionId(sessionKey: String, providerSessionId: String)
}
