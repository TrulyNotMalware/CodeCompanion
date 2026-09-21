package dev.notypie.impl.agent

interface AgentGateway {
    fun converse(request: AgentTurnRequest): AgentTurnResult
}

data class AgentTurnRequest(
    val sessionKey: String,
    val prompt: String,
    val sessionId: String? = null,
    val userId: String? = null,
    val appendSystemPrompt: String? = null,
    val scopedToken: String? = null,
)

sealed interface AgentTurnResult {
    data class Completed(
        val sessionId: String?,
        val finalText: String,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
    ) : AgentTurnResult

    data object Busy : AgentTurnResult

    data class Failed(
        val code: String,
        val message: String,
    ) : AgentTurnResult
}
