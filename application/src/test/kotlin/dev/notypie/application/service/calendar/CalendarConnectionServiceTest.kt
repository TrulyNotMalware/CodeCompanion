package dev.notypie.application.service.calendar

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.outbox.createStubTransactionManager
import dev.notypie.domain.TEST_APP_ID
import dev.notypie.domain.TEST_CHANNEL_ID
import dev.notypie.domain.TEST_USER_ID
import dev.notypie.domain.command.createCalendarConnectionRequestEvent
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CalendarConnectionAction
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.calendar.GoogleOAuthClient
import dev.notypie.impl.calendar.GoogleOAuthException
import dev.notypie.impl.calendar.GoogleTokenGrant
import dev.notypie.impl.calendar.TokenCipher
import dev.notypie.repository.calendar.ConnectionSaved
import dev.notypie.repository.calendar.GoogleCalendarConnectionRepository
import dev.notypie.repository.calendar.GoogleOAuthStateRepository
import dev.notypie.repository.calendar.schema.CalendarConnection
import dev.notypie.repository.calendar.schema.CalendarConnectionStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class CalendarConnectionServiceTest :
    BehaviorSpec({
        val now = Instant.parse("2026-10-07T01:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val cipher = TokenCipher(keyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        val appConfig =
            AppConfig(
                calendar =
                    AppConfig.Calendar(
                        google =
                            AppConfig.Calendar.Google(
                                enabled = true,
                                clientId = "cid",
                                clientSecret = "secret",
                                redirectUri = "https://bot.example.com/oauth/google/callback",
                                tokenEncryptionKey = "unused-here",
                                stateTtlMinutes = 10L,
                            ),
                    ),
            )
        val calendarScopes = setOf(GoogleOAuthClient.CALENDAR_EVENTS_SCOPE, "openid", "email")

        fun activeConnection(
            subject: String? = "sub-1",
            email: String? = "dev@example.com",
            status: CalendarConnectionStatus = CalendarConnectionStatus.ACTIVE,
        ) = CalendarConnection(
            slackUserId = TEST_USER_ID,
            googleSubject = subject,
            googleEmail = email,
            encryptedRefreshToken = cipher.encrypt(plaintext = "1//refresh"),
            status = status,
            connectedAt = now.minus(Duration.ofDays(1)),
            revokedAt = if (status == CalendarConnectionStatus.REVOKED) now.minus(Duration.ofHours(1)) else null,
            lastError = null,
        )

        fun grant(
            refreshToken: String = "1//fresh",
            scopes: Set<String> = calendarScopes,
            subject: String? = "sub-1",
            email: String? = "dev@example.com",
        ) = GoogleTokenGrant(
            accessToken = "ya29",
            refreshToken = refreshToken,
            expiresInSeconds = 3600L,
            scopes = scopes,
            subject = subject,
            email = email,
        )

        class Harness(
            val connections: GoogleCalendarConnectionRepository = mockk(),
            val states: GoogleOAuthStateRepository = mockk(),
            val oauth: GoogleOAuthClient = mockk(),
            cipher: TokenCipher,
            clock: Clock,
            appConfig: AppConfig,
        ) {
            val staged = mutableListOf<OutboundMessage>()
            val basicInfos = mutableListOf<CommandBasicInfo>()
            val revocations = mutableListOf<Any>()
            val stager =
                mockk<OutboundMessageStager> {
                    every { stage(message = capture(staged), basicInfo = capture(basicInfos)) } returns
                        mockk(relaxed = true)
                }
            val publisher = mockk<EventPublisher>(relaxed = true)
            val springEvents =
                mockk<ApplicationEventPublisher> {
                    every { publishEvent(capture(revocations)) } just Runs
                }
            val service =
                CalendarConnectionService(
                    connectionRepository = connections,
                    stateRepository = states,
                    oauthClient = oauth,
                    tokenCipher = cipher,
                    outboundStager = stager,
                    eventPublisher = publisher,
                    applicationEventPublisher = springEvents,
                    clock = clock,
                    transactionManager = createStubTransactionManager(),
                    appConfig = appConfig,
                )

            fun directMessage(): OutboundMessage.ChannelMessage =
                staged.filterIsInstance<OutboundMessage.ChannelMessage>().single()

            fun ephemeral(): OutboundMessage.Ephemeral = staged.filterIsInstance<OutboundMessage.Ephemeral>().single()

            fun revocation(): GoogleTokenRevocationRequested =
                revocations.single().shouldBeInstanceOf<GoogleTokenRevocationRequested>()

            fun OutboundMessage.ChannelMessage.markdown(): String =
                content.shouldBeInstanceOf<MessageContent.Text>().markdown

            fun OutboundMessage.Ephemeral.markdown(): String =
                content.shouldBeInstanceOf<MessageContent.Text>().markdown
        }

        fun harness(): Harness = Harness(cipher = cipher, clock = clock, appConfig = appConfig)

        given("`/meetup calendar connect`") {
            fun connectHarness(existing: CalendarConnection?): Harness =
                harness().apply {
                    every { states.issue(state = any(), userId = TEST_USER_ID, expiresAt = any()) } just Runs
                    every { oauth.authorizationUrl(state = any()) } answers {
                        "https://accounts.google.com/o/oauth2/v2/auth?client_id=cid&state=${firstArg<String>()}"
                    }
                    every { connections.find(userId = TEST_USER_ID) } returns existing
                }

            `when`("the requester has no connection yet") {
                val h = connectHarness(existing = null)
                h.service.handle(createCalendarConnectionRequestEvent(action = CalendarConnectionAction.CONNECT))
                val issuedState = slot<String>()
                verify {
                    h.states.issue(
                        state = capture(issuedState),
                        userId = TEST_USER_ID,
                        expiresAt = now.plus(Duration.ofMinutes(10)),
                    )
                }

                then(
                    "a single-use random state is issued with the configured TTL and nothing else touches the state table",
                ) {
                    issuedState.captured shouldMatch Regex("[A-Za-z0-9_-]{43}")
                    verify(exactly = 0) { h.states.deleteExpired(before = any()) }
                }

                then("the consent link goes to the requester as a DM, with the state and `&` escaped for mrkdwn") {
                    val dm = h.directMessage()
                    dm.target.id shouldBe TEST_USER_ID
                    dm.detailType shouldBe CommandDetailType.CALENDAR_CONNECTION
                    val expectedLink =
                        "<https://accounts.google.com/o/oauth2/v2/auth?client_id=cid&amp;state=" +
                            "${issuedState.captured}|Connect Google Calendar>"
                    with(h) {
                        dm.markdown() shouldStartWith expectedLink
                        dm.markdown() shouldContain "expires in 10 minutes"
                    }
                    val dmBasicInfo = h.basicInfos[h.staged.indexOf(dm)]
                    dmBasicInfo.publisherId shouldBe TEST_USER_ID
                    dmBasicInfo.channel shouldBe TEST_USER_ID
                    dmBasicInfo.appId shouldBe TEST_APP_ID
                }

                then("the channel reply only says a DM was sent, so the link never shows in the channel") {
                    val ephemeral = h.ephemeral()
                    ephemeral.target.id shouldBe TEST_CHANNEL_ID
                    ephemeral.recipient?.id shouldBe TEST_USER_ID
                    with(h) {
                        ephemeral.markdown() shouldBe
                            "I sent you a direct message with a link to connect your Google Calendar."
                    }
                }
            }

            `when`("the requester is already connected") {
                val h = connectHarness(existing = activeConnection())
                h.service.handle(createCalendarConnectionRequestEvent(action = CalendarConnectionAction.CONNECT))

                then("the reply warns that reconnecting replaces the existing account") {
                    with(h) {
                        ephemeral().markdown() shouldStartWith
                            "Google Calendar is already connected as dev@example.com; connecting again replaces it. "
                    }
                }
            }
        }

        given("`/meetup calendar disconnect`") {
            `when`("the requester has no connection") {
                val h = harness().apply { every { connections.find(userId = TEST_USER_ID) } returns null }
                h.service.handle(createCalendarConnectionRequestEvent(action = CalendarConnectionAction.DISCONNECT))

                then("nothing is deleted or revoked and the reply points at connect") {
                    with(h) {
                        ephemeral().markdown() shouldBe
                            "Google Calendar is not connected. ${CalendarConnectionService.CONNECT_USAGE}"
                    }
                    verify(exactly = 0) { h.connections.delete(userId = any()) }
                    h.revocations.shouldBeEmpty()
                }
            }

            `when`("the requester is connected") {
                val connection = activeConnection()
                val h =
                    harness().apply {
                        every { connections.find(userId = TEST_USER_ID) } returns connection
                        every { connections.delete(userId = TEST_USER_ID) } returns true
                        every { states.deleteForUser(userId = TEST_USER_ID) } returns 1
                    }
                h.service.handle(createCalendarConnectionRequestEvent(action = CalendarConnectionAction.DISCONNECT))

                then("the row and the user's unused consent links are deleted in the slash transaction") {
                    verify(exactly = 1) { h.connections.delete(userId = TEST_USER_ID) }
                    verify(exactly = 1) { h.states.deleteForUser(userId = TEST_USER_ID) }
                }

                then("the revoke is handed off with the account id, never called here") {
                    verify(exactly = 0) { h.oauth.revoke(token = any()) }
                    val revocation = h.revocation()
                    revocation.userId shouldBe TEST_USER_ID
                    revocation.googleSubject shouldBe "sub-1"
                    revocation.encryptedRefreshToken shouldBe connection.encryptedRefreshToken
                    revocation.reason shouldBe CalendarConnectionService.DISCONNECT_REASON
                }

                then("the reply says the access is being revoked") {
                    with(h) {
                        ephemeral().markdown() shouldBe
                            "Google Calendar disconnected. CodeCompanion's access at Google is being revoked."
                    }
                }
            }
        }

        given("`/meetup calendar status`") {
            `when`("there is no connection") {
                val h = harness().apply { every { connections.find(userId = TEST_USER_ID) } returns null }
                h.service.handle(createCalendarConnectionRequestEvent(action = CalendarConnectionAction.STATUS))

                then("the reply says so and points at connect") {
                    with(h) {
                        ephemeral().markdown() shouldBe
                            "Google Calendar is not connected. ${CalendarConnectionService.CONNECT_USAGE}"
                    }
                }
            }

            `when`("the connection is active") {
                val h = harness().apply { every { connections.find(userId = TEST_USER_ID) } returns activeConnection() }
                h.service.handle(createCalendarConnectionRequestEvent(action = CalendarConnectionAction.STATUS))

                then("the reply names the account and renders the time with Slack's viewer-local date token") {
                    with(h) {
                        ephemeral().markdown() shouldStartWith
                            "Google Calendar is connected as dev@example.com since <!date^"
                        ephemeral().markdown() shouldContain "^{date_short} {time}|"
                    }
                }
            }

            `when`("the connection was revoked by Google") {
                val h =
                    harness().apply {
                        every { connections.find(userId = TEST_USER_ID) } returns
                            activeConnection(status = CalendarConnectionStatus.REVOKED)
                    }
                h.service.handle(createCalendarConnectionRequestEvent(action = CalendarConnectionAction.STATUS))

                then("the reply asks the user to reconnect") {
                    with(h) {
                        ephemeral().markdown() shouldStartWith "Google Calendar access was revoked on <!date^"
                        ephemeral().markdown() shouldContain CalendarConnectionService.CONNECT_USAGE
                    }
                }
            }
        }

        given("the OAuth callback") {
            fun Harness.withConsumedState(state: String = "ok"): Harness =
                apply {
                    every { states.consume(state = state, now = now) } returns TEST_USER_ID
                    every { states.deleteExpired(before = now) } returns 0
                }

            fun Harness.savingConnection(
                storedToken: CapturingSlotHolder,
                replaced: CalendarConnection? = null,
            ): Harness =
                apply {
                    every {
                        connections.saveActive(
                            userId = TEST_USER_ID,
                            googleSubject = any(),
                            googleEmail = any(),
                            encryptedRefreshToken = capture(storedToken.slot),
                            now = now,
                        )
                    } answers {
                        ConnectionSaved(
                            connection =
                                CalendarConnection(
                                    slackUserId = TEST_USER_ID,
                                    googleSubject = "sub-1",
                                    googleEmail = "dev@example.com",
                                    encryptedRefreshToken = storedToken.slot.captured,
                                    status = CalendarConnectionStatus.ACTIVE,
                                    connectedAt = now,
                                    revokedAt = null,
                                    lastError = null,
                                ),
                            replaced = replaced,
                        )
                    }
                }

            `when`("the state parameter is missing") {
                val h = harness()

                then("the link is rejected before touching the database") {
                    h.service.completeConnection(code = "c", state = null, error = null) shouldBe
                        CalendarConnectionOutcome.INVALID_STATE
                    verify(exactly = 0) { h.states.consume(state = any(), now = any()) }
                }
            }

            `when`("the state is unknown, expired or already used") {
                val h = harness().apply { every { states.consume(state = "stale", now = now) } returns null }

                then("the link is rejected, nothing is purged and no code exchange happens") {
                    h.service.completeConnection(code = "c", state = "stale", error = null) shouldBe
                        CalendarConnectionOutcome.INVALID_STATE
                    verify(exactly = 0) { h.states.deleteExpired(before = any()) }
                    verify(exactly = 0) { h.oauth.exchangeCode(code = any()) }
                }
            }

            `when`("the user denied consent") {
                val h = harness().withConsumedState()

                then("the state is consumed, expired states are purged in that transaction, and no exchange happens") {
                    h.service.completeConnection(code = null, state = "ok", error = "access_denied") shouldBe
                        CalendarConnectionOutcome.DENIED
                    verify(exactly = 1) { h.states.consume(state = "ok", now = now) }
                    verify(exactly = 1) { h.states.deleteExpired(before = now) }
                    verify(exactly = 0) { h.oauth.exchangeCode(code = any()) }
                }
            }

            `when`("the state is valid but no code came back") {
                val h = harness().withConsumedState()

                then("the link is rejected") {
                    h.service.completeConnection(code = "", state = "ok", error = null) shouldBe
                        CalendarConnectionOutcome.INVALID_STATE
                }
            }

            `when`("Google rejects the code exchange") {
                val h =
                    harness().withConsumedState().apply {
                        every { oauth.exchangeCode(code = "bad") } throws
                            GoogleOAuthException(message = "rejected", statusCode = 400)
                    }

                then("nothing is stored and the outcome is EXCHANGE_FAILED") {
                    h.service.completeConnection(code = "bad", state = "ok", error = null) shouldBe
                        CalendarConnectionOutcome.EXCHANGE_FAILED
                    verify(exactly = 0) {
                        h.connections.saveActive(
                            userId = any(),
                            googleSubject = any(),
                            googleEmail = any(),
                            encryptedRefreshToken = any(),
                            now = any(),
                        )
                    }
                    h.staged.shouldBeEmpty()
                }
            }

            `when`("a first-time user unticked the calendar scope on Google's consent screen") {
                val h =
                    harness().withConsumedState().apply {
                        every { oauth.exchangeCode(code = "partial") } returns grant(scopes = setOf("openid", "email"))
                        every { connections.find(userId = TEST_USER_ID) } returns null
                        every { oauth.revoke(token = "1//fresh") } returns true
                    }
                val outcome = h.service.completeConnection(code = "partial", state = "ok", error = null)

                then("the new token is revoked at once, nothing is stored and the outcome is SCOPE_DENIED") {
                    outcome shouldBe CalendarConnectionOutcome.SCOPE_DENIED
                    verify(exactly = 1) { h.oauth.revoke(token = "1//fresh") }
                    verify(exactly = 0) {
                        h.connections.saveActive(
                            userId = any(),
                            googleSubject = any(),
                            googleEmail = any(),
                            encryptedRefreshToken = any(),
                            now = any(),
                        )
                    }
                    h.staged.shouldBeEmpty()
                }
            }

            `when`("an already connected user unticked the calendar scope") {
                val h =
                    harness().withConsumedState().apply {
                        every { oauth.exchangeCode(code = "partial") } returns grant(scopes = setOf("openid", "email"))
                        every { connections.find(userId = TEST_USER_ID) } returns activeConnection()
                    }
                val outcome = h.service.completeConnection(code = "partial", state = "ok", error = null)

                then("nothing is revoked, because Google's revoke is per grant and would kill the stored token too") {
                    outcome shouldBe CalendarConnectionOutcome.SCOPE_DENIED
                    verify(exactly = 0) { h.oauth.revoke(token = any()) }
                    h.staged.shouldBeEmpty()
                }
            }

            `when`("the exchange succeeds for a first connection") {
                val storedToken = CapturingSlotHolder()
                val h =
                    harness().withConsumedState().savingConnection(storedToken = storedToken).apply {
                        every { oauth.exchangeCode(code = "good") } returns grant()
                    }
                val outcome = h.service.completeConnection(code = "good", state = "ok", error = null)

                then("the refresh token is stored encrypted, never in clear") {
                    outcome shouldBe CalendarConnectionOutcome.CONNECTED
                    storedToken.slot.captured shouldStartWith "v1."
                    storedToken.slot.captured shouldNotContain "1//fresh"
                    cipher.decrypt(token = storedToken.slot.captured) shouldBe "1//fresh"
                }

                then("the user gets a DM naming the connected account and no revocation is requested") {
                    val dm = h.directMessage()
                    dm.target.id shouldBe TEST_USER_ID
                    with(h) { dm.markdown() shouldBe "Google Calendar connected as dev@example.com." }
                    h.revocations.shouldBeEmpty()
                }
            }

            `when`("the exchange succeeds for a user reconnecting the same Google account") {
                val storedToken = CapturingSlotHolder()
                val h =
                    harness()
                        .withConsumedState()
                        .savingConnection(storedToken = storedToken, replaced = activeConnection(subject = "sub-1"))
                        .apply { every { oauth.exchangeCode(code = "good") } returns grant(subject = "sub-1") }
                h.service.completeConnection(code = "good", state = "ok", error = null)

                then("the replaced token is left alone: revoking it would revoke the whole grant, new token included") {
                    h.revocations.shouldBeEmpty()
                }
            }

            `when`("the exchange succeeds for a user switching to another Google account") {
                val storedToken = CapturingSlotHolder()
                val replaced = activeConnection(subject = "sub-old", email = "old@example.com")
                val h =
                    harness()
                        .withConsumedState()
                        .savingConnection(storedToken = storedToken, replaced = replaced)
                        .apply { every { oauth.exchangeCode(code = "good") } returns grant(subject = "sub-new") }
                h.service.completeConnection(code = "good", state = "ok", error = null)

                then("the old account's grant is handed to the revocation worker with its account id") {
                    val revocation = h.revocation()
                    revocation.userId shouldBe TEST_USER_ID
                    revocation.googleSubject shouldBe "sub-old"
                    revocation.encryptedRefreshToken shouldBe replaced.encryptedRefreshToken
                    revocation.reason shouldBe CalendarConnectionService.RECONNECT_REASON
                }
            }

            `when`("two callbacks for a first-time user race and the second insert hits the unique key") {
                val storedToken = CapturingSlotHolder()
                val h =
                    harness().withConsumedState().apply {
                        every { oauth.exchangeCode(code = "good") } returns grant()
                        every {
                            connections.saveActive(
                                userId = TEST_USER_ID,
                                googleSubject = any(),
                                googleEmail = any(),
                                encryptedRefreshToken = capture(storedToken.slot),
                                now = now,
                            )
                        } throws DataIntegrityViolationException("uk_google_calendar_connection_user") andThenAnswer {
                            ConnectionSaved(
                                connection =
                                    CalendarConnection(
                                        slackUserId = TEST_USER_ID,
                                        googleSubject = "sub-1",
                                        googleEmail = "dev@example.com",
                                        encryptedRefreshToken = storedToken.slot.captured,
                                        status = CalendarConnectionStatus.ACTIVE,
                                        connectedAt = now,
                                        revokedAt = null,
                                        lastError = null,
                                    ),
                                replaced = null,
                            )
                        }
                    }
                val outcome = h.service.completeConnection(code = "good", state = "ok", error = null)

                then("the save is retried once as an update in a fresh transaction and the user is connected") {
                    outcome shouldBe CalendarConnectionOutcome.CONNECTED
                    verify(exactly = 2) {
                        h.connections.saveActive(
                            userId = TEST_USER_ID,
                            googleSubject = any(),
                            googleEmail = any(),
                            encryptedRefreshToken = any(),
                            now = now,
                        )
                    }
                    verify(exactly = 1) { h.oauth.exchangeCode(code = "good") }
                    h.directMessage().target.id shouldBe TEST_USER_ID
                }
            }

            `when`("the accounts are only known by e-mail and they differ") {
                val storedToken = CapturingSlotHolder()
                val replaced = activeConnection(subject = null, email = "Old@example.com")
                val h =
                    harness()
                        .withConsumedState()
                        .savingConnection(storedToken = storedToken, replaced = replaced)
                        .apply {
                            every { oauth.exchangeCode(code = "good") } returns
                                grant(subject = null, email = "new@example.com")
                        }
                h.service.completeConnection(code = "good", state = "ok", error = null)

                then("the e-mail comparison decides and the old grant is revoked") {
                    h.revocation().encryptedRefreshToken shouldBe replaced.encryptedRefreshToken
                }
            }

            `when`("neither the subject nor the e-mail of the replaced grant is known") {
                val storedToken = CapturingSlotHolder()
                val h =
                    harness()
                        .withConsumedState()
                        .savingConnection(
                            storedToken = storedToken,
                            replaced = activeConnection(subject = null, email = null),
                        ).apply { every { oauth.exchangeCode(code = "good") } returns grant() }
                h.service.completeConnection(code = "good", state = "ok", error = null)

                then("the revoke is skipped, since it might be the same account") {
                    h.revocations.shouldBeEmpty()
                }
            }
        }
    })

class CapturingSlotHolder {
    val slot = slot<String>()
}
