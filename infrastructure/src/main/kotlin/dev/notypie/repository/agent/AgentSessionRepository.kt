package dev.notypie.repository.agent

interface AgentSessionRepository {
    /** The AI backend's session id last recorded for [sessionKey], or null on a first turn. */
    fun findProviderSessionId(sessionKey: String): String?

    /** Records [providerSessionId] as the resume target for [sessionKey] (insert or update). */
    fun saveProviderSessionId(sessionKey: String, providerSessionId: String)
}
