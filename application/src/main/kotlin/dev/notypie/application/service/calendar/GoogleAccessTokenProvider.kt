package dev.notypie.application.service.calendar

import dev.notypie.impl.calendar.GoogleOAuthClient
import dev.notypie.impl.calendar.GoogleOAuthException
import dev.notypie.impl.calendar.TokenCipher
import dev.notypie.repository.calendar.GoogleCalendarConnectionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

enum class RevocationCause(
    val lastError: String,
) {
    GRANT_REJECTED(lastError = "refresh rejected: invalid_grant"),
    TOKEN_UNREADABLE(lastError = "stored token unreadable"),
}

sealed interface AccessTokenResult {
    data class Granted(
        val accessToken: String,
    ) : AccessTokenResult {
        override fun toString(): String = "Granted(accessToken=****)"
    }

    data object NotConnected : AccessTokenResult

    data class Revoked(
        val observedEncryptedRefreshToken: String,
        val cause: RevocationCause,
    ) : AccessTokenResult {
        override fun toString(): String = "Revoked(cause=$cause)"
    }

    data class Misconfigured(
        val message: String,
    ) : AccessTokenResult

    data class Unavailable(
        val message: String,
    ) : AccessTokenResult
}

class GoogleAccessTokenProvider(
    private val oauthClient: GoogleOAuthClient,
    private val tokenCipher: TokenCipher,
    private val connections: GoogleCalendarConnectionRepository,
    private val clock: Clock,
) {
    private val cache = ConcurrentHashMap<String, CachedToken>()

    fun accessToken(userId: String): AccessTokenResult {
        val connection = connections.find(userId = userId)
        if (connection == null || !connection.isActive) {
            evict(userId = userId)
            return AccessTokenResult.NotConnected
        }
        val now = clock.instant()
        val cached = cache[userId]
        if (cached != null &&
            cached.encryptedRefreshToken == connection.encryptedRefreshToken &&
            now.isBefore(cached.expiresAt.minus(REFRESH_MARGIN))
        ) {
            return AccessTokenResult.Granted(accessToken = cached.accessToken)
        }
        val refreshToken =
            try {
                tokenCipher.decrypt(token = connection.encryptedRefreshToken)
            } catch (exception: IllegalArgumentException) {
                log.warn(exception) { "Stored Google refresh token could not be decrypted: userId=$userId" }
                return revoked(
                    userId = userId,
                    observedEncryptedRefreshToken = connection.encryptedRefreshToken,
                    cause = RevocationCause.TOKEN_UNREADABLE,
                )
            }
        val granted =
            try {
                oauthClient.refresh(refreshToken = refreshToken)
            } catch (exception: GoogleOAuthException) {
                return when (exception.error) {
                    INVALID_GRANT ->
                        revoked(
                            userId = userId,
                            observedEncryptedRefreshToken = connection.encryptedRefreshToken,
                            cause = RevocationCause.GRANT_REJECTED,
                        )

                    in CLIENT_ERRORS ->
                        AccessTokenResult.Misconfigured(
                            message = "Google rejected the OAuth client: ${exception.error}",
                        )

                    else -> {
                        log.warn {
                            "Google access token refresh failed: userId=$userId status=${exception.statusCode} " +
                                "error=${exception.error}"
                        }
                        AccessTokenResult.Unavailable(message = exception.message ?: "token refresh failed")
                    }
                }
            }
        val current = connections.find(userId = userId)
        if (current == null || !current.isActive || current.encryptedRefreshToken != connection.encryptedRefreshToken) {
            log.info { "Google Calendar connection changed while its token was refreshed: userId=$userId" }
            return AccessTokenResult.Unavailable(message = CHANGED_DURING_REFRESH)
        }
        cache[userId] =
            CachedToken(
                accessToken = granted.accessToken,
                expiresAt = now.plusSeconds(granted.expiresInSeconds),
                encryptedRefreshToken = connection.encryptedRefreshToken,
            )
        return AccessTokenResult.Granted(accessToken = granted.accessToken)
    }

    fun evict(userId: String) {
        cache.remove(userId)
    }

    private fun revoked(
        userId: String,
        observedEncryptedRefreshToken: String,
        cause: RevocationCause,
    ): AccessTokenResult {
        evict(userId = userId)
        log.warn { "Google Calendar grant unusable (${cause.lastError}): userId=$userId" }
        return AccessTokenResult.Revoked(observedEncryptedRefreshToken = observedEncryptedRefreshToken, cause = cause)
    }

    private data class CachedToken(
        val accessToken: String,
        val expiresAt: Instant,
        val encryptedRefreshToken: String,
    ) {
        override fun toString(): String = "CachedToken(expiresAt=$expiresAt)"
    }

    companion object {
        const val CHANGED_DURING_REFRESH: String = "connection changed during refresh"
        private const val INVALID_GRANT = "invalid_grant"
        private val CLIENT_ERRORS: Set<String> = setOf("invalid_client", "unauthorized_client")
        private val REFRESH_MARGIN: Duration = Duration.ofSeconds(60L)
    }
}
