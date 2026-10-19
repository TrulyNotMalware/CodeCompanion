package dev.notypie.schema

import dev.notypie.domain.TEST_USER_ID
import dev.notypie.impl.calendar.TokenCipher
import dev.notypie.repository.calendar.schema.CalendarConnection
import dev.notypie.repository.calendar.schema.CalendarConnectionStatus
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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
    TokenCipher(keyBase64 = Base64.getEncoder().encodeToString(testTokenKey(seed = seed)))

fun createLegacyEncryptedToken(plaintext: String, seed: Int = 7): String {
    val iv = ByteArray(12) { (it + 1).toByte() }
    val cipher: Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(testTokenKey(seed = seed), "AES"), GCMParameterSpec(128, iv))
        }
    val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    return "v1.${encoder.encodeToString(
        iv,
    )}.${encoder.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))}"
}

private fun testTokenKey(seed: Int): ByteArray = ByteArray(32) { seed.toByte() }
