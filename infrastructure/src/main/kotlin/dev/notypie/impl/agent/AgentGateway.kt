package dev.notypie.impl.agent

/**
 * Port to the AI-agent backend (the claude-sidecar co-process). Deliberately narrow: one blocking
 * turn in, one terminal result out. Streaming/tool events are consumed internally by the adapter —
 * Slack has no token-streaming surface, so callers only need the terminal outcome.
 */
interface AgentGateway {
    fun converse(request: AgentTurnRequest): AgentTurnResult
}

/**
 * One conversation turn. [sessionKey] scopes the sidecar's per-conversation workspace (stable per
 * Slack thread); [sessionId] is the backend's own session id from a prior [AgentTurnResult.Completed]
 * and must be echoed back to actually resume the conversation context. [userId] is forwarded as
 * `X-User-Id` for per-user concurrency gating and MCP tool attribution — the model never sees it.
 * [appendSystemPrompt] is appended to the sidecar's static base prompt (CLAUDE.md) for this turn —
 * the seam for per-request context like requester, channel, and current time.
 */
data class AgentTurnRequest(
    val sessionKey: String,
    val prompt: String,
    val sessionId: String? = null,
    val userId: String? = null,
    val appendSystemPrompt: String? = null,
)

sealed interface AgentTurnResult {
    /** Terminal `done`: [sessionId] must be persisted and echoed on the next turn to resume. */
    data class Completed(
        val sessionId: String?,
        val finalText: String,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
    ) : AgentTurnResult

    /** The backend already runs a turn for this sessionKey/user (HTTP 429 or SSE `busy`). */
    data object Busy : AgentTurnResult

    /** Terminal `error` frame, non-2xx response, or transport failure. [code] mirrors the sidecar error codes. */
    data class Failed(
        val code: String,
        val message: String,
    ) : AgentTurnResult
}
