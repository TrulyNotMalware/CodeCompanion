package dev.notypie.application.security.mcp

import java.time.Instant

const val SCOPED_TURN_TOKEN_CONTEXT_KEY = "dev.notypie.mcp.scoped-turn-token"

data class ScopedTurnToken(
    val userId: String,
    val sessionKey: String,
    val turnId: String,
    val expiresAt: Instant,
)
