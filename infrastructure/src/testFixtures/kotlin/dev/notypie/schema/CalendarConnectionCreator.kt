package dev.notypie.schema

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.impl.calendar.TokenCipher
import dev.notypie.repository.calendar.schema.CalendarConnection
import dev.notypie.repository.calendar.schema.CalendarConnectionStatus
import java.time.Instant
import java.util.Base64

fun createCalendarConnection(
    slackUserId: String = TEST_USER_ID,
    googleSubject: String? = "sub-1",
    googleEmail: String? = "dev@example.com",
    encryptedRefreshToken: String = "v1.test-iv.test-ciphertext",
    status: CalendarConnectionStatus = CalendarConnectionStatus.ACTIVE,
    connectedAt: Instant = Instant.parse("2026-10-06T01:00:00Z"),
    revokedAt: Instant? = null,
    lastError: String? = null,
): CalendarConnection =
    CalendarConnection(
        slackUserId = slackUserId,
        googleSubject = googleSubject,
        googleEmail = googleEmail,
        encryptedRefreshToken = encryptedRefreshToken,
        status = status,
        connectedAt = connectedAt,
        revokedAt = revokedAt,
        lastError = lastError,
    )

fun createTestTokenCipher(seed: Int = 7): TokenCipher =
    TokenCipher(keyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { seed.toByte() }))
