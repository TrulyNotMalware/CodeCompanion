package dev.notypie.application.service.calendar

import dev.notypie.application.outbox.createStubTransactionManager
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.calendar.GoogleOAuthClient
import dev.notypie.repository.calendar.GoogleCalendarConnectionRepository
import dev.notypie.repository.calendar.schema.CalendarConnection
import dev.notypie.schema.createCalendarConnection
import dev.notypie.schema.createTestTokenCipher
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.dao.DataAccessResourceFailureException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class GoogleTokenRevocationWorkerTest :
    BehaviorSpec({
        val cipher = createTestTokenCipher()
        val encrypted = cipher.encrypt(plaintext = "1//refresh")

        class Harness(
            executor: Executor = Executor { task -> task.run() },
            current: CalendarConnection? = null,
        ) {
            val oauth = mockk<GoogleOAuthClient>()
            val connections =
                mockk<GoogleCalendarConnectionRepository> {
                    every { find(userId = "U_CAL") } returns current
                }
            val staged = mutableListOf<OutboundMessage>()
            val stager =
                mockk<OutboundMessageStager> {
                    every { stage(message = capture(staged), basicInfo = any()) } returns mockk(relaxed = true)
                }
            val worker =
                GoogleTokenRevocationWorker(
                    oauthClient = oauth,
                    tokenCipher = cipher,
                    outboundStager = stager,
                    eventPublisher = mockk<EventPublisher>(relaxed = true),
                    connectionRepository = connections,
                    transactionManager = createStubTransactionManager(),
                    executor = executor,
                )

            fun hintDm(): OutboundMessage.ChannelMessage =
                staged.single().shouldBeInstanceOf<OutboundMessage.ChannelMessage>()
        }

        val event =
            GoogleTokenRevocationRequested(
                userId = "U_CAL",
                googleSubject = "sub-1",
                encryptedRefreshToken = encrypted,
                reason = "disconnect",
            )

        given("a revocation request after the slash transaction committed") {
            `when`("Google confirms the revoke") {
                val h = Harness().apply { every { oauth.revoke(token = "1//refresh") } returns true }
                h.worker.onRevocationRequested(event)

                then("the stored token is decrypted, revoked on the executor and the user hears nothing more") {
                    verify(exactly = 1) { h.oauth.revoke(token = "1//refresh") }
                    h.staged.shouldBeEmpty()
                }
            }

            `when`("Google does not confirm the revoke") {
                val h = Harness().apply { every { oauth.revoke(token = "1//refresh") } returns false }
                h.worker.onRevocationRequested(event)

                then("the user gets a DM with the manual-removal hint") {
                    val dm = h.hintDm()
                    dm.target.id shouldBe "U_CAL"
                    dm.content.shouldBeInstanceOf<MessageContent.Text>().markdown shouldBe
                        GoogleTokenRevocationWorker.MANUAL_REMOVAL_HINT
                }
            }

            `when`("the stored token cannot be decrypted (key rotated)") {
                val h = Harness()
                h.worker.onRevocationRequested(event.copy(encryptedRefreshToken = "v1.garbage.garbage"))

                then("no revoke is attempted and the manual-removal hint is sent") {
                    verify(exactly = 0) { h.oauth.revoke(token = any()) }
                    h.hintDm().target.id shouldBe "U_CAL"
                }
            }

            `when`("the executor rejects the task") {
                val h = Harness(executor = Executor { throw RejectedExecutionException("full") })
                h.worker.onRevocationRequested(event)

                then("the manual-removal hint is sent instead of losing the request silently") {
                    verify(exactly = 0) { h.oauth.revoke(token = any()) }
                    h.hintDm().target.id shouldBe "U_CAL"
                }
            }

            `when`("the task fails before the revoke because the connection cannot be read (database outage)") {
                val h =
                    Harness().apply {
                        every { connections.find(userId = "U_CAL") } throws
                            DataAccessResourceFailureException("database down")
                    }

                then("nothing escapes the executor task, no revoke is attempted and the manual-removal hint is sent") {
                    shouldNotThrowAny { h.worker.onRevocationRequested(event) }
                    verify(exactly = 0) { h.oauth.revoke(token = any()) }
                    h.hintDm().target.id shouldBe "U_CAL"
                }
            }
        }

        given("a revocation that was queued behind a reconnect") {
            `when`("the user is connected again with the same Google account") {
                val h = Harness(current = createCalendarConnection(slackUserId = "U_CAL", googleSubject = "sub-1"))
                h.worker.onRevocationRequested(event)

                then("the revoke is skipped, because it would revoke the grant now in use") {
                    verify(exactly = 0) { h.oauth.revoke(token = any()) }
                    h.staged.shouldBeEmpty()
                }
            }

            `when`("the user is connected again but the account is unknown on either side") {
                val h = Harness(current = createCalendarConnection(slackUserId = "U_CAL", googleSubject = null))
                h.worker.onRevocationRequested(event)

                then("the revoke is skipped as well") {
                    verify(exactly = 0) { h.oauth.revoke(token = any()) }
                }
            }

            `when`("the user is connected again with another Google account") {
                val h =
                    Harness(
                        current = createCalendarConnection(slackUserId = "U_CAL", googleSubject = "sub-other"),
                    ).apply {
                        every {
                            oauth.revoke(token = "1//refresh")
                        } returns true
                    }
                h.worker.onRevocationRequested(event)

                then("the old account's grant is still revoked") {
                    verify(exactly = 1) { h.oauth.revoke(token = "1//refresh") }
                }
            }
        }

        given("the event's toString") {
            then("it never prints the token") {
                event.toString() shouldBe
                    "GoogleTokenRevocationRequested(userId=U_CAL, googleSubject=sub-1, reason=disconnect)"
            }
        }
    })
