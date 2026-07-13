package dev.notypie.application.security.mcp

import java.time.Instant

fun createScopedTurnToken(
    userId: String = "U_REQUESTER",
    sessionKey: String = "C012ABCDEFG:1751.0001",
    turnId: String = "11111111-2222-3333-4444-555555555555",
    expiresAt: Instant = Instant.parse("2026-07-08T12:05:00Z"),
) = ScopedTurnToken(
    userId = userId,
    sessionKey = sessionKey,
    turnId = turnId,
    expiresAt = expiresAt,
)
