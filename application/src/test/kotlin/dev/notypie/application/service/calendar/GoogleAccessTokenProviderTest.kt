package dev.notypie.application.service.calendar

import dev.notypie.application.outbox.MutableClock
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.impl.calendar.GoogleAccessToken
import dev.notypie.impl.calendar.GoogleOAuthClient
import dev.notypie.impl.calendar.GoogleOAuthException
import dev.notypie.repository.calendar.GoogleCalendarConnectionRepository
import dev.notypie.repository.calendar.schema.CalendarConnection
import dev.notypie.repository.calendar.schema.CalendarConnectionStatus
import dev.notypie.schema.createCalendarConnection
import dev.notypie.schema.createTestTokenCipher
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class GoogleAccessTokenProviderTest :
    BehaviorSpec({
        val start = Instant.parse("2026-10-08T01:00:00Z")
        val cipher = createTestTokenCipher()
        val storedToken = cipher.encrypt(plaintext = "1//refresh")

        fun connection(
            status: CalendarConnectionStatus = CalendarConnectionStatus.ACTIVE,
            encryptedRefreshToken: String = storedToken,
        ) = createCalendarConnection(encryptedRefreshToken = encryptedRefreshToken, status = status)

        class Harness(
            stored: CalendarConnection?,
        ) {
            val clock = MutableClock(current = start, zoneId = ZoneOffset.UTC)
            val oauth = mockk<GoogleOAuthClient>()
            val connections = mockk<GoogleCalendarConnectionRepository>()
            val provider =
                GoogleAccessTokenProvider(
                    oauthClient = oauth,
                    tokenCipher = cipher,
                    connections = connections,
                    clock = clock,
                )

            init {
                every { connections.find(userId = TEST_USER_ID) } returns stored
            }

            fun verifyNoRevocationWrite() {
                verify(exactly = 0) {
                    connections.markRevoked(
                        userId = any(),
                        observedEncryptedRefreshToken = any(),
                        now = any(),
                        reason = any(),
                    )
                }
            }
        }

        given("an active connection whose refresh succeeds") {
            `when`("the token is asked for twice within its lifetime") {
                val h = Harness(stored = connection())
                every { h.oauth.refresh(refreshToken = "1//refresh") } returns
                    GoogleAccessToken(accessToken = "ya29.first", expiresInSeconds = 3600L)

                val first = h.provider.accessToken(userId = TEST_USER_ID)
                h.clock.advance(by = Duration.ofSeconds(3539L))
                val second = h.provider.accessToken(userId = TEST_USER_ID)

                then("the decrypted refresh token is exchanged once and the cached token is reused") {
                    first shouldBe AccessTokenResult.Granted(accessToken = "ya29.first")
                    second shouldBe AccessTokenResult.Granted(accessToken = "ya29.first")
                    verify(exactly = 1) { h.oauth.refresh(refreshToken = "1//refresh") }
                }

                then("the stored connection is read on every request, and once more right after the refresh") {
                    verify(exactly = 3) { h.connections.find(userId = TEST_USER_ID) }
                }

                then("the granted result never prints the token") {
                    first.toString() shouldNotContain "ya29"
                }
            }

            `when`("the cached token is within 60 seconds of expiring") {
                val h = Harness(stored = connection())
                every { h.oauth.refresh(refreshToken = "1//refresh") } returns
                    GoogleAccessToken(accessToken = "ya29.first", expiresInSeconds = 3600L) andThen
                    GoogleAccessToken(accessToken = "ya29.second", expiresInSeconds = 3600L)

                h.provider.accessToken(userId = TEST_USER_ID)
                h.clock.advance(by = Duration.ofSeconds(3540L))
                val renewed = h.provider.accessToken(userId = TEST_USER_ID)

                then("it is refreshed instead of reused") {
                    renewed shouldBe AccessTokenResult.Granted(accessToken = "ya29.second")
                    verify(exactly = 2) { h.oauth.refresh(refreshToken = "1//refresh") }
                }
            }

            `when`("a reconnect replaced the stored refresh token while a token was cached") {
                val h = Harness(stored = connection())
                every { h.oauth.refresh(refreshToken = "1//refresh") } returns
                    GoogleAccessToken(accessToken = "ya29.old-account", expiresInSeconds = 3600L)
                every { h.oauth.refresh(refreshToken = "1//new") } returns
                    GoogleAccessToken(accessToken = "ya29.new-account", expiresInSeconds = 3600L)

                h.provider.accessToken(userId = TEST_USER_ID)
                every { h.connections.find(userId = TEST_USER_ID) } returns
                    connection(encryptedRefreshToken = cipher.encrypt(plaintext = "1//new"))
                val afterReconnect = h.provider.accessToken(userId = TEST_USER_ID)

                then("the cached token of the replaced grant is not reused") {
                    afterReconnect shouldBe AccessTokenResult.Granted(accessToken = "ya29.new-account")
                    verify(exactly = 1) { h.oauth.refresh(refreshToken = "1//new") }
                }
            }

            `when`("the user disconnects while a token is cached") {
                val h = Harness(stored = connection())
                every { h.oauth.refresh(refreshToken = "1//refresh") } returns
                    GoogleAccessToken(accessToken = "ya29.first", expiresInSeconds = 3600L)

                h.provider.accessToken(userId = TEST_USER_ID)
                every { h.connections.find(userId = TEST_USER_ID) } returns null
                val afterDisconnect = h.provider.accessToken(userId = TEST_USER_ID)

                then("the result is NotConnected at once, not the cached token") {
                    afterDisconnect shouldBe AccessTokenResult.NotConnected
                }
            }

            `when`("the cached token is evicted") {
                val h = Harness(stored = connection())
                every { h.oauth.refresh(refreshToken = "1//refresh") } returns
                    GoogleAccessToken(accessToken = "ya29.first", expiresInSeconds = 3600L) andThen
                    GoogleAccessToken(accessToken = "ya29.second", expiresInSeconds = 3600L)

                h.provider.accessToken(userId = TEST_USER_ID)
                h.provider.evict(userId = TEST_USER_ID)
                val renewed = h.provider.accessToken(userId = TEST_USER_ID)

                then("the next request refreshes again") {
                    renewed shouldBe AccessTokenResult.Granted(accessToken = "ya29.second")
                    verify(exactly = 2) { h.oauth.refresh(refreshToken = "1//refresh") }
                }
            }
        }

        given("a user without a usable connection") {
            listOf(
                "no row" to null,
                "a REVOKED row" to connection(status = CalendarConnectionStatus.REVOKED),
            ).forEach { (label, stored) ->
                `when`("the user has $label") {
                    val h = Harness(stored = stored)
                    val result = h.provider.accessToken(userId = TEST_USER_ID)

                    then("the result is NotConnected and Google is not called") {
                        result shouldBe AccessTokenResult.NotConnected
                        verify(exactly = 0) { h.oauth.refresh(refreshToken = any()) }
                    }
                }
            }

            `when`("the stored token cannot be decrypted") {
                val h = Harness(stored = connection(encryptedRefreshToken = "v1.not-a-token"))
                val result = h.provider.accessToken(userId = TEST_USER_ID)

                then(
                    "it is reported like a revoked grant, so the user is asked to reconnect, and Google is not called",
                ) {
                    result shouldBe
                        AccessTokenResult.Revoked(
                            observedEncryptedRefreshToken = "v1.not-a-token",
                            cause = RevocationCause.TOKEN_UNREADABLE,
                        )
                    RevocationCause.TOKEN_UNREADABLE.lastError shouldBe "stored token unreadable"
                    verify(exactly = 0) { h.oauth.refresh(refreshToken = any()) }
                    h.verifyNoRevocationWrite()
                }
            }
        }

        given("Google rejects the refresh token with invalid_grant") {
            val h = Harness(stored = connection())
            every { h.oauth.refresh(refreshToken = "1//refresh") } throws
                GoogleOAuthException(message = "invalid_grant", statusCode = 400, error = "invalid_grant")
            val result = h.provider.accessToken(userId = TEST_USER_ID)

            then("the result names the ciphertext that was refreshed and the cause, and nothing is written here") {
                result shouldBe
                    AccessTokenResult.Revoked(
                        observedEncryptedRefreshToken = storedToken,
                        cause = RevocationCause.GRANT_REJECTED,
                    )
                RevocationCause.GRANT_REJECTED.lastError shouldBe "refresh rejected: invalid_grant"
                h.verifyNoRevocationWrite()
            }

            then("the observation never prints the stored ciphertext") {
                result.toString() shouldBe "Revoked(cause=GRANT_REJECTED)"
            }
        }

        given("Google rejects the OAuth client itself") {
            listOf("invalid_client", "unauthorized_client").forEach { error ->
                `when`("the refresh fails with $error") {
                    val h = Harness(stored = connection())
                    every { h.oauth.refresh(refreshToken = "1//refresh") } throws
                        GoogleOAuthException(message = error, statusCode = 401, error = error)
                    val result = h.provider.accessToken(userId = TEST_USER_ID)

                    then("the result is Misconfigured with the error code, and the connection is left alone") {
                        result shouldBe
                            AccessTokenResult.Misconfigured(message = "Google rejected the OAuth client: $error")
                        h.verifyNoRevocationWrite()
                    }
                }
            }
        }

        given("a refresh that succeeds while the stored connection changes") {
            listOf(
                "a disconnect deleted the row" to null,
                "the row was revoked" to connection(status = CalendarConnectionStatus.REVOKED),
                "a reconnect stored another token" to
                    connection(encryptedRefreshToken = cipher.encrypt(plaintext = "1//new")),
            ).forEach { (label, afterRefresh) ->
                `when`("$label meanwhile") {
                    val h = Harness(stored = connection())
                    every { h.connections.find(userId = TEST_USER_ID) } returnsMany listOf(connection(), afterRefresh)
                    every { h.oauth.refresh(refreshToken = "1//refresh") } returns
                        GoogleAccessToken(accessToken = "ya29.stale", expiresInSeconds = 3600L) andThen
                        GoogleAccessToken(accessToken = "ya29.second", expiresInSeconds = 3600L)

                    val result = h.provider.accessToken(userId = TEST_USER_ID)
                    every { h.connections.find(userId = TEST_USER_ID) } returns connection()
                    val next = h.provider.accessToken(userId = TEST_USER_ID)

                    then("the refreshed token is neither returned nor cached") {
                        result shouldBe AccessTokenResult.Unavailable(message = "connection changed during refresh")
                        next shouldBe AccessTokenResult.Granted(accessToken = "ya29.second")
                        verify(exactly = 2) { h.oauth.refresh(refreshToken = "1//refresh") }
                    }
                }
            }
        }

        given("Google fails the refresh for another reason") {
            val h = Harness(stored = connection())
            every { h.oauth.refresh(refreshToken = "1//refresh") } throws
                GoogleOAuthException(message = "Google token endpoint returned 503", statusCode = 503, error = null)

            val result = h.provider.accessToken(userId = TEST_USER_ID)

            then("the result is Unavailable with Google's message and the connection is left alone") {
                result shouldBe AccessTokenResult.Unavailable(message = "Google token endpoint returned 503")
                h.verifyNoRevocationWrite()
            }
        }
    })
