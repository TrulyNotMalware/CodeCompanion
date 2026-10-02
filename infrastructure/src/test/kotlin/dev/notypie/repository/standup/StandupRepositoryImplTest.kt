package dev.notypie.repository.standup

import dev.notypie.domain.standup.createSessionDispatch
import dev.notypie.domain.standup.createStandupSession
import dev.notypie.domain.standup.entity.enums.DispatchStatus
import dev.notypie.domain.standup.entity.enums.SessionStatus
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val LOCK_HOLD_MILLIS = 300L

private const val MARIADB_MODE_H2_URL =
    "jdbc:h2:mem:standup_mariadb;MODE=MariaDB;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"

@DataJpaTest(properties = ["spring.datasource.url=$MARIADB_MODE_H2_URL"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ApplyExtension(extensions = [SpringExtension::class])
class StandupRepositoryImplTest
    @Autowired
    constructor(
        private val jpaRoutineRepository: JpaRoutineRepository,
        private val jpaStandupSessionRepository: JpaStandupSessionRepository,
        private val jpaSessionDispatchRepository: JpaSessionDispatchRepository,
        transactionManager: PlatformTransactionManager,
    ) : BehaviorSpec({
            val repository =
                StandupRepositoryImpl(
                    jpaRoutineRepository = jpaRoutineRepository,
                    jpaStandupSessionRepository = jpaStandupSessionRepository,
                    jpaSessionDispatchRepository = jpaSessionDispatchRepository,
                )
            val transactionTemplate = TransactionTemplate(transactionManager)
            val cutoffAt = Instant.parse("2026-05-04T03:00:00Z")

            fun <T : Any> inTx(action: () -> T): T = transactionTemplate.execute { action() }!!

            fun openSession(status: SessionStatus = SessionStatus.COLLECTING, summaryMessageTs: String? = null): UUID =
                inTx {
                    repository
                        .createSession(
                            session =
                                createStandupSession(
                                    routineUid = UUID.randomUUID(),
                                    cutoffAt = cutoffAt,
                                    status = status,
                                    summaryMessageTs = summaryMessageTs,
                                    dispatches = listOf(createSessionDispatch(userId = "U_A")),
                                ),
                        ).sessionUid
                }

            fun record(sessionUid: UUID, responses: List<String>, submittedAt: Instant) =
                inTx {
                    repository.recordAnswer(
                        sessionUid = sessionUid,
                        userId = "U_A",
                        responses = responses,
                        submittedAt = submittedAt,
                    )
                }

            given("a member who answers a collecting session twice") {
                `when`("the same member resubmits in a later transaction") {
                    val sessionUid = openSession()
                    val first =
                        record(
                            sessionUid = sessionUid,
                            responses = listOf("first", "draft"),
                            submittedAt = cutoffAt.minusSeconds(600L),
                        )

                    then("the second submission replaces the row instead of violating uk_standup_answer") {
                        first shouldBe AnswerRecordResult.RECORDED
                        shouldNotThrowAny {
                            record(
                                sessionUid = sessionUid,
                                responses = listOf("second", "final"),
                                submittedAt = cutoffAt.minusSeconds(300L),
                            )
                        } shouldBe AnswerRecordResult.RECORDED
                        val answers = repository.findSession(sessionUid = sessionUid)!!.answers
                        answers.map { it.userId } shouldContainExactly listOf("U_A")
                        answers.single().responses shouldContainExactly listOf("second", "final")
                        answers.single().submittedAt shouldBe cutoffAt.minusSeconds(300L)
                    }
                }
            }

            given("two first submissions by one member, the later deciding from a view older than the first") {
                `when`("the second transaction read the session before the first answer committed") {
                    val sessionUid = openSession()
                    val staleViewTaken = CountDownLatch(1)
                    val firstCommitted = CountDownLatch(1)
                    val secondResult = AtomicReference<Result<AnswerRecordResult>>()
                    val second =
                        thread {
                            secondResult.set(
                                runCatching {
                                    inTx {
                                        repository.findSession(sessionUid = sessionUid)
                                        staleViewTaken.countDown()
                                        firstCommitted.await(5L, TimeUnit.SECONDS)
                                        repository.recordAnswer(
                                            sessionUid = sessionUid,
                                            userId = "U_A",
                                            responses = listOf("second"),
                                            submittedAt = cutoffAt.minusSeconds(60L),
                                        )
                                    }
                                },
                            )
                        }
                    staleViewTaken.await(5L, TimeUnit.SECONDS)
                    val first =
                        record(
                            sessionUid = sessionUid,
                            responses = listOf("first"),
                            submittedAt = cutoffAt.minusSeconds(120L),
                        )
                    firstCommitted.countDown()
                    second.join()

                    then("both are recorded and the member keeps one row with the later answer, no unique violation") {
                        first shouldBe AnswerRecordResult.RECORDED
                        secondResult.get().getOrThrow() shouldBe AnswerRecordResult.RECORDED
                        val answers = repository.findSession(sessionUid = sessionUid)!!.answers
                        answers.single().responses shouldContainExactly listOf("second")
                        answers.single().submittedAt shouldBe cutoffAt.minusSeconds(60L)
                    }
                }
            }

            given("recordAnswer on a session that no longer collects answers") {
                `when`("the session was already summarized") {
                    val sessionUid = openSession(status = SessionStatus.SUMMARIZED, summaryMessageTs = "outbox:1")
                    val result =
                        record(
                            sessionUid = sessionUid,
                            responses = listOf("late"),
                            submittedAt = cutoffAt.minusSeconds(60L),
                        )

                    then("it is rejected as closed and no answer row is written") {
                        result shouldBe AnswerRecordResult.SESSION_CLOSED
                        repository.findSession(sessionUid = sessionUid)!!.answers.shouldBeEmpty()
                    }
                }

                `when`("the session is still COLLECTING but the answer arrives at or after cutoff") {
                    val sessionUid = openSession()
                    val result = record(sessionUid = sessionUid, responses = listOf("late"), submittedAt = cutoffAt)

                    then("it is rejected as closed, since the summary may already be reading the answers") {
                        result shouldBe AnswerRecordResult.SESSION_CLOSED
                        repository.findSession(sessionUid = sessionUid)!!.answers.shouldBeEmpty()
                    }
                }

                `when`("the session uid is unknown") {
                    val result = record(sessionUid = UUID.randomUUID(), responses = listOf("x"), submittedAt = cutoffAt)

                    then("it reports SESSION_NOT_FOUND") {
                        result shouldBe AnswerRecordResult.SESSION_NOT_FOUND
                    }
                }

                `when`("the answering transaction already holds the session and the summary commits before the lock") {
                    val sessionUid = openSession()
                    val result =
                        inTx {
                            val loaded = repository.findSession(sessionUid = sessionUid)!!
                            thread {
                                inTx {
                                    repository.markSessionSummarized(
                                        sessionId = loaded.sessionId,
                                        messageTs = "outbox:between",
                                    )
                                }
                            }.join()
                            repository.recordAnswer(
                                sessionUid = sessionUid,
                                userId = "U_A",
                                responses = listOf("raced"),
                                submittedAt = cutoffAt.minusSeconds(30L),
                            )
                        }

                    then("the locked read sees SUMMARIZED, not the managed instance's stale COLLECTING") {
                        result shouldBe AnswerRecordResult.SESSION_CLOSED
                        repository.findSession(sessionUid = sessionUid)!!.answers.shouldBeEmpty()
                    }
                }
            }

            given("recordAnswer racing the cutoff summary on the same session") {
                `when`("the summary locks the session first and commits SUMMARIZED") {
                    val sessionUid = openSession()
                    val summaryLocked = CountDownLatch(1)
                    val summaryFailure = AtomicReference<Throwable?>()
                    val summary =
                        thread {
                            runCatching {
                                inTx {
                                    val session = repository.findSessionForSummary(sessionUid = sessionUid)!!
                                    summaryLocked.countDown()
                                    Thread.sleep(LOCK_HOLD_MILLIS)
                                    repository.markSessionSummarized(
                                        sessionId = session.sessionId,
                                        messageTs = "outbox:race",
                                    )
                                }
                            }.onFailure { summaryFailure.set(it) }
                            summaryLocked.countDown()
                        }
                    summaryLocked.await(5L, TimeUnit.SECONDS)
                    val result =
                        record(
                            sessionUid = sessionUid,
                            responses = listOf("just before cutoff"),
                            submittedAt = cutoffAt.minusSeconds(1L),
                        )
                    summary.join()

                    then("the answer waits for the summary and is told closed instead of submitted") {
                        summaryFailure.get() shouldBe null
                        result shouldBe AnswerRecordResult.SESSION_CLOSED
                        repository.findSession(sessionUid = sessionUid)!!.answers.shouldBeEmpty()
                    }
                }

                `when`("the answer locks the session first and commits") {
                    val sessionUid = openSession()
                    val answerLocked = CountDownLatch(1)
                    val answerResult = AtomicReference<AnswerRecordResult?>()
                    val answer =
                        thread {
                            runCatching {
                                inTx {
                                    repository
                                        .recordAnswer(
                                            sessionUid = sessionUid,
                                            userId = "U_A",
                                            responses = listOf("made it"),
                                            submittedAt = cutoffAt.minusSeconds(1L),
                                        ).also {
                                            answerLocked.countDown()
                                            Thread.sleep(LOCK_HOLD_MILLIS)
                                        }
                                }
                            }.onSuccess { answerResult.set(it) }
                            answerLocked.countDown()
                        }
                    answerLocked.await(5L, TimeUnit.SECONDS)
                    val summaryRead = inTx { repository.findSessionForSummary(sessionUid = sessionUid)!! }
                    answer.join()

                    then("the summary read waits for the answer's commit and includes it") {
                        answerResult.get() shouldBe AnswerRecordResult.RECORDED
                        summaryRead.answers.map { it.userId } shouldContainExactly listOf("U_A")
                    }
                }
            }

            given("recordDispatchFailure") {
                `when`("an enqueue attempt failed for a dispatch that is PENDING again, and for one already claimed") {
                    val pendingUid = openSession()
                    val claimedUid = openSession()
                    val pendingId =
                        repository
                            .findSession(sessionUid = pendingUid)!!
                            .dispatches
                            .single()
                            .id
                    val claimedId =
                        repository
                            .findSession(sessionUid = claimedUid)!!
                            .dispatches
                            .single()
                            .id
                    repository.claimDispatch(dispatchId = claimedId, claimToken = "token", now = cutoffAt) shouldBe true
                    val recorded =
                        inTx {
                            repository.recordDispatchFailure(dispatchId = pendingId, reason = "timeout", now = cutoffAt)
                        }
                    val ignored =
                        inTx {
                            repository.recordDispatchFailure(dispatchId = claimedId, reason = "timeout", now = cutoffAt)
                        }

                    then("the PENDING row keeps the cause and stays queued; the claimed row is left to its claim") {
                        recorded shouldBe true
                        ignored shouldBe false
                        val pending = repository.findSession(sessionUid = pendingUid)!!.dispatches.single()
                        pending.dmStatus shouldBe DispatchStatus.PENDING
                        pending.failureReason shouldBe "timeout"
                        repository
                            .findPendingDispatchesBefore(before = Instant.parse("2100-01-01T00:00:00Z"), limit = 500)
                            .single { it.dispatch.id == pendingId }
                            .dispatch
                            .failureReason shouldBe "timeout"
                        repository
                            .findSession(sessionUid = claimedUid)!!
                            .dispatches
                            .single()
                            .failureReason shouldBe null
                    }
                }
            }

            given("markDispatchSkipped") {
                `when`("a PENDING dispatch is skipped twice") {
                    val sessionUid = openSession()
                    val dispatchId =
                        repository
                            .findSession(sessionUid = sessionUid)!!
                            .dispatches
                            .single()
                            .id
                    val first =
                        inTx {
                            repository.markDispatchSkipped(
                                dispatchId = dispatchId,
                                reason = "closed",
                                now = cutoffAt,
                            )
                        }
                    val second =
                        inTx {
                            repository.markDispatchSkipped(
                                dispatchId = dispatchId,
                                reason = "again",
                                now = cutoffAt,
                            )
                        }

                    then("the row ends once, as FAILED with a skipped: reason the previous release can read") {
                        first shouldBe true
                        second shouldBe false
                        val dispatch = repository.findSession(sessionUid = sessionUid)!!.dispatches.single()
                        dispatch.dmStatus shouldBe DispatchStatus.FAILED
                        dispatch.failureReason shouldBe "skipped: closed"
                        repository
                            .findPendingDispatchesBefore(before = Instant.parse("2100-01-01T00:00:00Z"), limit = 500)
                            .map { it.dispatch.id } shouldNotContain dispatchId
                    }
                }
            }
        })
