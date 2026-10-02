package dev.notypie.repository.standup

import dev.notypie.domain.standup.createSessionDispatch
import dev.notypie.domain.standup.createStandupAnswer
import dev.notypie.domain.standup.createStandupSession
import dev.notypie.domain.standup.entity.enums.DispatchStatus
import dev.notypie.domain.standup.entity.enums.SessionStatus
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

// Long enough for the other thread to reach the lock, well under H2's lock timeout.
private const val LOCK_HOLD_MILLIS = 300L

private const val MARIADB_MODE_H2_URL =
    "jdbc:h2:mem:standup_mariadb;MODE=MariaDB;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"

// Real Hibernate + H2 so SQL ordering (IDENTITY inserts vs. orphan deletes) is what production sees. H2 runs in
// MariaDB mode here, on a database of its own, because recordAnswer's INSERT … ON DUPLICATE KEY UPDATE is
// MariaDB syntax that H2's default mode rejects (review G4).
@DataJpaTest(properties = ["spring.datasource.url=$MARIADB_MODE_H2_URL"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ApplyExtension(extensions = [SpringExtension::class])
class StandupRepositoryImplJpaTest
    @Autowired
    constructor(
        private val jpaRoutineRepository: JpaRoutineRepository,
        private val jpaStandupSessionRepository: JpaStandupSessionRepository,
        private val jpaSessionDispatchRepository: JpaSessionDispatchRepository,
        private val jdbcTemplate: JdbcTemplate,
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

            afterSpec {
                jpaStandupSessionRepository.deleteAll()
            }

            given("a session with several dispatches and several answers (G1 / G12)") {
                `when`("each read that maps the whole session graph loads it") {
                    val routineUid = UUID.randomUUID()
                    val sessionDate = LocalDate.of(2026, 5, 4)
                    val sessionUid =
                        inTx {
                            repository
                                .createSession(
                                    session =
                                        createStandupSession(
                                            routineUid = routineUid,
                                            sessionDate = sessionDate,
                                            cutoffAt = cutoffAt,
                                            dispatches =
                                                listOf("U_A", "U_B", "U_C").map { createSessionDispatch(userId = it) },
                                            answers = listOf("U_A", "U_B").map { createStandupAnswer(userId = it) },
                                        ),
                                ).sessionUid
                        }

                    then("every answer comes back once, not once per dispatch row of the join") {
                        val bySessionUid = repository.findSession(sessionUid = sessionUid)!!
                        bySessionUid.dispatches.size shouldBe 3
                        bySessionUid.answers.map { it.userId } shouldContainExactlyInAnyOrder listOf("U_A", "U_B")
                        repository
                            .findSession(routineUid = routineUid, sessionDate = sessionDate)!!
                            .answers.size shouldBe 2
                        repository
                            .findCollectingSessionsPastCutoff(before = cutoffAt.plusSeconds(1L))
                            .single { it.sessionUid == sessionUid }
                            .answers.size shouldBe 2
                    }
                }
            }

            given("recordAnswer for a member who already answered (T9)") {
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
                        answers.size shouldBe 1
                        answers.single().responses shouldContainExactly listOf("second", "final")
                        answers.single().submittedAt shouldBe cutoffAt.minusSeconds(300L)
                    }
                }
            }

            given("two first submissions by one member, the later deciding from a view older than the first (G4)") {
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
                                        // The interaction transaction read first — what a REPEATABLE READ snapshot or
                                        // an entity already in the persistence context shows: no answer yet.
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
                        answers.size shouldBe 1
                        answers.single().responses shouldContainExactly listOf("second")
                        answers.single().submittedAt shouldBe cutoffAt.minusSeconds(60L)
                    }
                }
            }

            given("recordAnswer on a session that no longer collects answers (T19)") {
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
                    val result =
                        record(sessionUid = UUID.randomUUID(), responses = listOf("x"), submittedAt = cutoffAt)

                    then("it reports SESSION_NOT_FOUND") {
                        result shouldBe AnswerRecordResult.SESSION_NOT_FOUND
                    }
                }
            }

            // Two real transactions on two threads: one holds the session row lock while the other runs, so the test
            // fails if either side reads the session without taking the lock (H2 is READ COMMITTED).
            given("recordAnswer racing the cutoff summary on the same session (G5 / Codex R5)") {
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

            given("markDispatchSkipped (T19 / T28, stored rollback-safe per G2)") {
                `when`("a PENDING dispatch is skipped") {
                    val sessionUid = openSession()
                    val dispatchId =
                        repository
                            .findSession(sessionUid = sessionUid)!!
                            .dispatches
                            .single()
                            .id
                    val first = inTx { repository.markDispatchSkipped(dispatchId = dispatchId, reason = "closed") }
                    val second = inTx { repository.markDispatchSkipped(dispatchId = dispatchId, reason = "again") }

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

                `when`("a row holds SKIPPED, as the next release will write it, and this release reads it back") {
                    val sessionUid = openSession()
                    val dispatchId =
                        repository
                            .findSession(sessionUid = sessionUid)!!
                            .dispatches
                            .single()
                            .id
                    jdbcTemplate.update(
                        "UPDATE standup_session_dispatch SET dm_status = 'SKIPPED' WHERE id = ?",
                        dispatchId,
                    )

                    then("it maps without failing, so a rollback from the next release to this one stays safe") {
                        repository
                            .findSession(sessionUid = sessionUid)!!
                            .dispatches
                            .single()
                            .dmStatus shouldBe DispatchStatus.SKIPPED
                    }
                }
            }
        })
