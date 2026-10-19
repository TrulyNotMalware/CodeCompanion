package dev.notypie.repository.calendar

import dev.notypie.repository.calendar.schema.CalendarConnectionStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class GoogleCalendarConnectionRepositoryImplTest
    @Autowired
    constructor(
        private val jpaGoogleCalendarConnectionRepository: JpaGoogleCalendarConnectionRepository,
        private val entityManager: TestEntityManager,
        transactionManager: PlatformTransactionManager,
    ) : BehaviorSpec({
            val repository =
                GoogleCalendarConnectionRepositoryImpl(
                    jpaGoogleCalendarConnectionRepository = jpaGoogleCalendarConnectionRepository,
                )
            val first = Instant.parse("2026-10-07T10:00:00Z")
            val second = Instant.parse("2026-10-08T10:00:00Z")
            val transactionTemplate =
                TransactionTemplate(transactionManager).apply {
                    propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                }

            fun <T : Any> inTx(action: () -> T): T = transactionTemplate.execute { action() }

            fun reconnect(userId: String, token: String, subject: String): ConnectionSaved =
                repository.saveActive(
                    userId = userId,
                    googleSubject = subject,
                    googleEmail = "$subject@example.com",
                    encryptedRefreshToken = token,
                    now = second,
                )

            fun <R, T : Any> loseToReconnect(
                userId: String,
                read: () -> R,
                write: (R) -> T,
                winnerCommits: () -> ConnectionSaved = {
                    reconnect(userId = userId, token = "v2.winner", subject = "sub-winner")
                },
            ): Pair<Result<ConnectionSaved>, Result<T>> {
                val loserHasRead = CountDownLatch(1)
                val winnerCommitted = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(2)
                val loser =
                    executor.submit(
                        Callable {
                            runCatching {
                                inTx {
                                    val observed = read()
                                    loserHasRead.countDown()
                                    check(
                                        winnerCommitted.await(5L, TimeUnit.SECONDS),
                                    ) { "the winner must commit first" }
                                    write(observed)
                                }
                            }
                        },
                    )
                val winner =
                    executor.submit(
                        Callable {
                            runCatching {
                                check(loserHasRead.await(5L, TimeUnit.SECONDS)) { "the loser must read first" }
                                inTx { winnerCommits() }
                            }.also { winnerCommitted.countDown() }
                        },
                    )
                try {
                    return winner.get(10L, TimeUnit.SECONDS) to loser.get(10L, TimeUnit.SECONDS)
                } finally {
                    executor.shutdownNow()
                }
            }

            given("a user who connects, reconnects with another account and then disconnects") {
                `when`("saveActive runs twice for the same user") {
                    then(
                        "one row is kept and rewritten with the newer token, email and time, and the replaced token is reported",
                    ) {
                        val firstSave =
                            repository.saveActive(
                                userId = "U_CAL",
                                googleSubject = "sub-a",
                                googleEmail = "a@example.com",
                                encryptedRefreshToken = "v1.a",
                                now = first,
                            )
                        val secondSave =
                            repository.saveActive(
                                userId = "U_CAL",
                                googleSubject = "sub-b",
                                googleEmail = "b@example.com",
                                encryptedRefreshToken = "v1.b",
                                now = second,
                            )
                        entityManager.flush()
                        entityManager.clear()

                        firstSave.replaced.shouldBeNull()
                        val replaced = secondSave.replaced.shouldNotBeNull()
                        replaced.encryptedRefreshToken shouldBe "v1.a"
                        replaced.googleSubject shouldBe "sub-a"
                        replaced.googleEmail shouldBe "a@example.com"
                        secondSave.toString() shouldNotContain "v1."

                        val connection = repository.find(userId = "U_CAL").shouldNotBeNull()
                        connection.googleSubject shouldBe "sub-b"
                        connection.googleEmail shouldBe "b@example.com"
                        connection.encryptedRefreshToken shouldBe "v1.b"
                        connection.connectedAt shouldBe second
                        connection.status shouldBe CalendarConnectionStatus.ACTIVE
                        connection.isActive shouldBe true
                        jpaGoogleCalendarConnectionRepository.findAll().count { it.slackUserId == "U_CAL" } shouldBe 1
                    }
                }

                `when`("the connection is deleted") {
                    then("find returns nothing and a second delete fails as a lost race") {
                        repository.saveActive(
                            userId = "U_GONE",
                            googleSubject = null,
                            googleEmail = null,
                            encryptedRefreshToken = "v1.x",
                            now = first,
                        )
                        entityManager.flush()

                        repository.delete(userId = "U_GONE", observedEncryptedRefreshToken = "v1.x")
                        entityManager.flush()
                        entityManager.clear()
                        repository.find(userId = "U_GONE").shouldBeNull()
                        shouldThrow<ObjectOptimisticLockingFailureException> {
                            repository.delete(userId = "U_GONE", observedEncryptedRefreshToken = "v1.x")
                        }
                    }
                }
            }

            given("active and revoked connections") {
                `when`("each user is checked for an active connection") {
                    then("only an ACTIVE row counts; a REVOKED row and an unknown user do not") {
                        listOf("U_ACT_1", "U_REV_1").forEach { userId ->
                            repository.saveActive(
                                userId = userId,
                                googleSubject = null,
                                googleEmail = null,
                                encryptedRefreshToken = "v1.$userId",
                                now = first,
                            )
                        }
                        entityManager.flush()
                        repository.markRevoked(
                            userId = "U_REV_1",
                            observedEncryptedRefreshToken = "v1.U_REV_1",
                            now = second,
                            reason = "invalid_grant",
                        ) shouldBe true
                        entityManager.flush()
                        entityManager.clear()

                        repository.hasActiveConnection(userId = "U_ACT_1") shouldBe true
                        repository.hasActiveConnection(userId = "U_REV_1") shouldBe false
                        repository.hasActiveConnection(userId = "U_NOBODY") shouldBe false
                    }
                }
            }

            given("a connection whose grant Google revoked") {
                `when`("it is marked revoked with the token the worker read, then again, and for a stranger") {
                    then(
                        "only the call on the ACTIVE row succeeds, and it records the time, the reason " +
                            "and updated_at in the JVM zone",
                    ) {
                        repository.saveActive(
                            userId = "U_RVK",
                            googleSubject = "sub-r",
                            googleEmail = "r@example.com",
                            encryptedRefreshToken = "v1.r",
                            now = first,
                        )
                        entityManager.flush()

                        repository.markRevoked(
                            userId = "U_RVK",
                            observedEncryptedRefreshToken = "v1.r",
                            now = second,
                            reason = "invalid_grant",
                        ) shouldBe true
                        entityManager.flush()
                        entityManager.clear()
                        repository.markRevoked(
                            userId = "U_RVK",
                            observedEncryptedRefreshToken = "v1.r",
                            now = second.plusSeconds(60L),
                            reason = "invalid_grant",
                        ) shouldBe false
                        repository.markRevoked(
                            userId = "U_STRANGER",
                            observedEncryptedRefreshToken = "v1.r",
                            now = second,
                            reason = "invalid_grant",
                        ) shouldBe false
                        entityManager.flush()
                        entityManager.clear()

                        val connection = repository.find(userId = "U_RVK").shouldNotBeNull()
                        connection.status shouldBe CalendarConnectionStatus.REVOKED
                        connection.isActive shouldBe false
                        connection.revokedAt shouldBe second
                        connection.lastError shouldBe "invalid_grant"
                        connection.encryptedRefreshToken shouldBe "v1.r"
                        jpaGoogleCalendarConnectionRepository
                            .findBySlackUserId(slackUserId = "U_RVK")
                            .shouldNotBeNull()
                            .updatedAt shouldBe LocalDateTime.ofInstant(second, ZoneId.systemDefault())
                    }
                }

                `when`("a reconnect replaced the token after the worker read the row") {
                    then("the update misses and the reconnected row stays ACTIVE with the new token") {
                        repository.saveActive(
                            userId = "U_RECON",
                            googleSubject = "sub-old",
                            googleEmail = "old@example.com",
                            encryptedRefreshToken = "v1.old",
                            now = first,
                        )
                        entityManager.flush()
                        repository.saveActive(
                            userId = "U_RECON",
                            googleSubject = "sub-new",
                            googleEmail = "new@example.com",
                            encryptedRefreshToken = "v1.new",
                            now = second,
                        )
                        entityManager.flush()
                        entityManager.clear()

                        repository.markRevoked(
                            userId = "U_RECON",
                            observedEncryptedRefreshToken = "v1.old",
                            now = second.plusSeconds(60L),
                            reason = "invalid_grant",
                        ) shouldBe false
                        entityManager.flush()
                        entityManager.clear()

                        val connection = repository.find(userId = "U_RECON").shouldNotBeNull()
                        connection.status shouldBe CalendarConnectionStatus.ACTIVE
                        connection.encryptedRefreshToken shouldBe "v1.new"
                        connection.revokedAt.shouldBeNull()
                        connection.lastError.shouldBeNull()
                    }
                }
            }

            given("a connection that another transaction rewrites after this one read it") {
                `when`("two reconnects of one user read the same row and the other commits first") {
                    inTx { reconnect(userId = "U_RACE_SAVE", token = "v2.seed", subject = "sub-seed") }
                    val (winner, loser) =
                        loseToReconnect(
                            userId = "U_RACE_SAVE",
                            read = { reconnect(userId = "U_RACE_SAVE", token = "v2.loser", subject = "sub-loser") },
                            write = { saved -> saved },
                        )

                    then("the later commit fails its version check instead of overwriting the grant just stored") {
                        winner
                            .getOrThrow()
                            .replaced
                            .shouldNotBeNull()
                            .encryptedRefreshToken shouldBe "v2.seed"
                        loser.exceptionOrNull().shouldBeInstanceOf<ObjectOptimisticLockingFailureException>()
                        val stored =
                            jpaGoogleCalendarConnectionRepository
                                .findBySlackUserId(slackUserId = "U_RACE_SAVE")
                                .shouldNotBeNull()
                        stored.encryptedRefreshToken shouldBe "v2.winner"
                        stored.googleSubject shouldBe "sub-winner"
                        stored.version shouldBe 1L
                    }
                }

                `when`("a disconnect read the row before a reconnect committed and deletes it afterwards") {
                    inTx { reconnect(userId = "U_RACE_DEL", token = "v2.seed", subject = "sub-seed") }
                    val (winner, loser) =
                        loseToReconnect(
                            userId = "U_RACE_DEL",
                            read = { repository.find(userId = "U_RACE_DEL").shouldNotBeNull() },
                            write = { observed ->
                                repository.delete(
                                    userId = "U_RACE_DEL",
                                    observedEncryptedRefreshToken = observed.encryptedRefreshToken,
                                )
                            },
                        )

                    then("the delete fails its version check and the reconnected row survives") {
                        winner.isSuccess shouldBe true
                        loser.exceptionOrNull().shouldBeInstanceOf<ObjectOptimisticLockingFailureException>()
                        repository.find(userId = "U_RACE_DEL").shouldNotBeNull().encryptedRefreshToken shouldBe
                            "v2.winner"
                    }
                }

                `when`("a disconnect read the row, another disconnect deleted it and a connect inserted a new row") {
                    inTx { reconnect(userId = "U_RACE_NEW", token = "v2.seed", subject = "sub-seed") }
                    val (winner, loser) =
                        loseToReconnect(
                            userId = "U_RACE_NEW",
                            read = { repository.find(userId = "U_RACE_NEW").shouldNotBeNull() },
                            write = { observed ->
                                repository.delete(
                                    userId = "U_RACE_NEW",
                                    observedEncryptedRefreshToken = observed.encryptedRefreshToken,
                                )
                            },
                            winnerCommits = {
                                repository.delete(userId = "U_RACE_NEW", observedEncryptedRefreshToken = "v2.seed")
                                reconnect(userId = "U_RACE_NEW", token = "v2.fresh", subject = "sub-fresh")
                            },
                        )

                    then("the stale delete misses the new row's ciphertext and the fresh connection survives") {
                        winner.getOrThrow().replaced.shouldBeNull()
                        loser.exceptionOrNull().shouldBeInstanceOf<ObjectOptimisticLockingFailureException>()
                        val stored =
                            jpaGoogleCalendarConnectionRepository
                                .findBySlackUserId(slackUserId = "U_RACE_NEW")
                                .shouldNotBeNull()
                        stored.encryptedRefreshToken shouldBe "v2.fresh"
                        stored.version shouldBe 0L
                    }
                }

                `when`("the worker marks the row revoked between a reconnect's read and its commit") {
                    inTx { reconnect(userId = "U_RACE_RVK", token = "v2.seed", subject = "sub-seed") }
                    val revokedBetween =
                        inTx {
                            val saved = reconnect(userId = "U_RACE_RVK", token = "v2.new", subject = "sub-new")
                            inTx {
                                repository.markRevoked(
                                    userId = "U_RACE_RVK",
                                    observedEncryptedRefreshToken = "v2.seed",
                                    now = second,
                                    reason = "invalid_grant",
                                )
                            } to saved
                        }

                    then("the bulk revocation leaves the version alone, so the reconnect still commits and wins") {
                        revokedBetween.first shouldBe true
                        val stored =
                            jpaGoogleCalendarConnectionRepository
                                .findBySlackUserId(slackUserId = "U_RACE_RVK")
                                .shouldNotBeNull()
                        stored.status shouldBe CalendarConnectionStatus.ACTIVE
                        stored.encryptedRefreshToken shouldBe "v2.new"
                        stored.revokedAt.shouldBeNull()
                        stored.version shouldBe 1L
                    }
                }
            }

            given("a user who never connected") {
                then("find returns nothing") {
                    repository.find(userId = "U_NONE").shouldBeNull()
                }
            }
        })
