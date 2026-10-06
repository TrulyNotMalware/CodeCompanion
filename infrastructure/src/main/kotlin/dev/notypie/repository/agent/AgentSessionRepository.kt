package dev.notypie.repository.agent

import java.time.LocalDateTime

interface AgentSessionRepository {
    fun findProviderSessionId(sessionKey: String): String?

    fun saveProviderSessionId(sessionKey: String, providerSessionId: String, now: LocalDateTime)
}
