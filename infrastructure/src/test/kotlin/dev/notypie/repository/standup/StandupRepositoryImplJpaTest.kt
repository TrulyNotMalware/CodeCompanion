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

// Real Hibernate + H2 so SQL ordering (IDENTITY inserts vs. orphan deletes) is what production sees.
@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class StandupRepositoryImplJpaTest
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

            given("markDispatchSkipped (T19 / T28)") {
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

                    then("the row turns SKIPPED once, keeps the first reason, and leaves the pending queue") {
                        first shouldBe true
                        second shouldBe false
                        val dispatch = repository.findSession(sessionUid = sessionUid)!!.dispatches.single()
                        dispatch.dmStatus shouldBe DispatchStatus.SKIPPED
                        dispatch.failureReason shouldBe "closed"
                        repository
                            .findPendingDispatchesBefore(before = Instant.parse("2100-01-01T00:00:00Z"), limit = 500)
                            .map { it.dispatch.id } shouldNotContain dispatchId
                    }
                }
            }
        })
