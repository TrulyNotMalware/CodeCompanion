package dev.notypie.application.security.mcp

import java.time.Instant

/** McpTransportContext key under which the verified token travels from extractor to tools. */
const val SCOPED_TURN_TOKEN_CONTEXT_KEY = "dev.notypie.mcp.scoped-turn-token"

/**
 * Identity carried by one agent turn into MCP tool calls. Holds no role — the caller's
 * role is re-resolved on every tool call so revocation applies mid-conversation.
 */
data class ScopedTurnToken(
    val userId: String,
    val sessionKey: String,
    val turnId: String,
    val expiresAt: Instant,
)
