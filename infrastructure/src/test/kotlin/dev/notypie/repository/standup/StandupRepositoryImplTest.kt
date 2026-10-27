package dev.notypie.repository.standup

import dev.notypie.domain.standup.createRoutine
import dev.notypie.domain.standup.createSessionDispatch
import dev.notypie.domain.standup.createStandupSession
import dev.notypie.domain.standup.entity.SessionDispatch
import dev.notypie.domain.standup.entity.enums.DispatchStatus
import dev.notypie.domain.standup.entity.enums.SessionStatus
import dev.notypie.exception.meeting.DatabaseException
import dev.notypie.schema.createRoutineStopCandidate
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val LOCK_HOLD_MILLIS = 300L

private const val BLOCK_PROBE_MILLIS = 500L

private const val HANDSHAKE_TIMEOUT_SECONDS = 10L

private data class HeldLockOutcome<H, W>(
    val held: H,
    val waited: W,
    val waiterBlockedWhileHeld: Boolean,
)

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

            fun <T : Any> inTx(action: () -> T): T = transactionTemplate.execute { action() }

            fun openSession(
                status: SessionStatus = SessionStatus.COLLECTING,
                summaryMessageTs: String? = null,
                dispatches: List<SessionDispatch> = listOf(createSessionDispatch(userId = "U_A")),
            ): UUID =
                inTx {
                    repository
                        .createSession(
                            session =
                                createStandupSession(
                                    routineUid = UUID.randomUUID(),
                                    cutoffAt = cutoffAt,
                                    status = status,
                                    summaryMessageTs = summaryMessageTs,
                                    dispatches = dispatches,
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

            fun skip(sessionUid: UUID, skippedAt: Instant) =
                inTx { repository.recordSkip(sessionUid = sessionUid, userId = "U_A", skippedAt = skippedAt) }

            fun <H : Any, W : Any> withHeldLock(
                holder: (hold: () -> Unit) -> H,
                waiter: () -> W,
            ): HeldLockOutcome<H, W> {
                val acquired = CountDownLatch(1)
                val release = CountDownLatch(1)
                val waiterStarted = CountDownLatch(1)
                val waiterDone = CountDownLatch(1)
                val held = AtomicReference<H?>()
                val waited = AtomicReference<W?>()
                val failures = ConcurrentLinkedQueue<Throwable>()
                val joinMillis = TimeUnit.SECONDS.toMillis(HANDSHAKE_TIMEOUT_SECONDS)
                val holderThread =
                    thread {
                        runCatching {
                            held.set(
                                inTx {
                                    holder {
                                        acquired.countDown()
                                        check(release.await(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                            "the holder was never released"
                                        }
                                    }
                                },
                            )
                        }.onFailure { failures.add(it) }
                        acquired.countDown()
                    }
                var waiterThread: Thread? = null
                var blocked = false
                try {
                    check(
                        acquired.await(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    ) { "the holder never took its lock" }
                    waiterThread =
                        thread {
                            runCatching {
                                waited.set(
                                    inTx {
                                        waiterStarted.countDown()
                                        waiter()
                                    },
                                )
                            }.onFailure { failures.add(it) }
                            waiterDone.countDown()
                        }
                    check(waiterStarted.await(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        "the waiter never started its transaction"
                    }
                    blocked = !waiterDone.await(BLOCK_PROBE_MILLIS, TimeUnit.MILLISECONDS)
                } catch (cause: Throwable) {
                    failures.add(cause)
                } finally {
                    release.countDown()
                    listOfNotNull(holderThread, waiterThread).forEach { worker ->
                        worker.join(joinMillis)
                        if (worker.isAlive) {
                            worker.interrupt()
                            failures.add(IllegalStateException("worker ${worker.name} did not finish"))
                        }
                    }
                }
                failures.toList() shouldBe emptyList()
                return HeldLockOutcome(
                    held = held.get().shouldNotBeNull(),
                    waited = waited.get().shouldNotBeNull(),
                    waiterBlockedWhileHeld = blocked,
                )
            }

            fun createActiveRoutine(name: String, creatorId: String, commandChannel: String) =
                inTx {
                    repository.createRoutine(
                        routine = createRoutine(name = name, creatorId = creatorId, commandChannel = commandChannel),
                    )
                }

            given("lockActiveRoutinesByChannel") {
                `when`("a channel holds two active routines and a stopped one, and another channel holds one more") {
                    val channel = "C_LOCK_${UUID.randomUUID()}"
                    val first =
                        createActiveRoutine(name = "Daily Sync", creatorId = "U_FIRST", commandChannel = channel)
                    val stopped = createActiveRoutine(name = "Old Sync", creatorId = "U_OLD", commandChannel = channel)
                    val second = createActiveRoutine(name = "Retro", creatorId = "U_SECOND", commandChannel = channel)
                    createActiveRoutine(name = "Elsewhere", creatorId = "U_FIRST", commandChannel = "${channel}_OTHER")
                    inTx { repository.deactivateRoutine(routineUid = stopped.routineUid) }

                    val candidates = inTx { repository.lockActiveRoutinesByChannel(commandChannel = channel) }

                    then("only that channel's active routines come back in id order with uid, name and creator") {
                        candidates shouldContainExactly
                            listOf(
                                createRoutineStopCandidate(
                                    routineUid = first.routineUid,
                                    name = "Daily Sync",
                                    creatorId = "U_FIRST",
                                ),
                                createRoutineStopCandidate(
                                    routineUid = second.routineUid,
                                    name = "Retro",
                                    creatorId = "U_SECOND",
                                ),
                            )
                    }
                }

                `when`("one stop holds the lock while a second transaction deactivates the same routine") {
                    val channel = "C_LOCK_${UUID.randomUUID()}"
                    val routine =
                        createActiveRoutine(name = "Daily Sync", creatorId = "U_FIRST", commandChannel = channel)
                    val lockHeld = CountDownLatch(1)
                    val holderStopped = AtomicReference<Boolean?>()
                    val holder =
                        thread {
                            runCatching {
                                inTx {
                                    val candidate =
                                        repository.lockActiveRoutinesByChannel(commandChannel = channel).single()
                                    lockHeld.countDown()
                                    Thread.sleep(LOCK_HOLD_MILLIS)
                                    repository.deactivateRoutine(routineUid = candidate.routineUid)
                                }
                            }.onSuccess { holderStopped.set(it) }
                            lockHeld.countDown()
                        }
                    lockHeld.await(5L, TimeUnit.SECONDS)
                    val secondStopped = inTx { repository.deactivateRoutine(routineUid = routine.routineUid) }
                    holder.join()

                    then("the second waits for the holder's commit and finds the routine already stopped") {
                        holderStopped.get() shouldBe true
                        secondStopped shouldBe false
                    }
                }
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
                        val answers = repository.findSession(sessionUid = sessionUid).shouldNotBeNull().answers
                        answers.map { it.userId } shouldContainExactly listOf("U_A")
                        answers.single().responses shouldContainExactly listOf("second", "final")
                        answers.single().submittedAt shouldBe cutoffAt.minusSeconds(300L)
                    }
                }
            }

            given("two first submissions by one member") {
                `when`("the second transaction starts with the session lock and waits while the first commits") {
                    val sessionUid = openSession()
                    val firstLocked = CountDownLatch(1)
                    val firstResult = AtomicReference<AnswerRecordResult?>()
                    val first =
                        thread {
                            runCatching {
                                inTx {
                                    repository
                                        .recordAnswer(
                                            sessionUid = sessionUid,
                                            userId = "U_A",
                                            responses = listOf("first"),
                                            submittedAt = cutoffAt.minusSeconds(120L),
                                        ).also {
                                            firstLocked.countDown()
                                            Thread.sleep(LOCK_HOLD_MILLIS)
                                        }
                                }
                            }.onSuccess { firstResult.set(it) }
                            firstLocked.countDown()
                        }
                    firstLocked.await(5L, TimeUnit.SECONDS)
                    val second =
                        record(
                            sessionUid = sessionUid,
                            responses = listOf("second"),
                            submittedAt = cutoffAt.minusSeconds(60L),
                        )
                    first.join()

                    then("both are recorded and the member keeps one row with the later answer, no unique violation") {
                        firstResult.get() shouldBe AnswerRecordResult.RECORDED
                        second shouldBe AnswerRecordResult.RECORDED
                        val answers = repository.findSession(sessionUid = sessionUid).shouldNotBeNull().answers
                        answers.single().responses shouldContainExactly listOf("second")
                        answers.single().submittedAt shouldBe cutoffAt.minusSeconds(60L)
                    }
                }

                `when`(
                    "the second transaction read the session before the first answer committed " +
                        "(not the production order: MariaDB snapshot isolation fails that upsert with 1020)",
                ) {
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

                    then("H2 records both and keeps the later answer in one row") {
                        first shouldBe AnswerRecordResult.RECORDED
                        secondResult.get().getOrThrow() shouldBe AnswerRecordResult.RECORDED
                        val answers = repository.findSession(sessionUid = sessionUid).shouldNotBeNull().answers
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
                        repository
                            .findSession(sessionUid = sessionUid)
                            .shouldNotBeNull()
                            .answers
                            .shouldBeEmpty()
                    }
                }

                `when`("the session is still COLLECTING but the answer arrives at or after cutoff") {
                    val sessionUid = openSession()
                    val result = record(sessionUid = sessionUid, responses = listOf("late"), submittedAt = cutoffAt)

                    then("it is rejected as closed, since the summary may already be reading the answers") {
                        result shouldBe AnswerRecordResult.SESSION_CLOSED
                        repository
                            .findSession(sessionUid = sessionUid)
                            .shouldNotBeNull()
                            .answers
                            .shouldBeEmpty()
                    }
                }

                `when`("the session uid is unknown") {
                    val result = record(sessionUid = UUID.randomUUID(), responses = listOf("x"), submittedAt = cutoffAt)

                    then("it reports SESSION_NOT_FOUND") {
                        result shouldBe AnswerRecordResult.SESSION_NOT_FOUND
                    }
                }

                `when`("the summary commits before the answering transaction, whose first statement is the lock") {
                    val sessionUid = openSession()
                    val sessionId = repository.findSession(sessionUid = sessionUid).shouldNotBeNull().sessionId
                    inTx { repository.markSessionSummarized(sessionId = sessionId, messageTs = "outbox:before") }
                    val result =
                        record(
                            sessionUid = sessionUid,
                            responses = listOf("late"),
                            submittedAt = cutoffAt.minusSeconds(30L),
                        )

                    then("it is rejected as closed and no answer row is written") {
                        result shouldBe AnswerRecordResult.SESSION_CLOSED
                        repository
                            .findSession(sessionUid = sessionUid)
                            .shouldNotBeNull()
                            .answers
                            .shouldBeEmpty()
                    }
                }

                `when`(
                    "the answering transaction read the session before the summary committed " +
                        "(not the production order: MariaDB snapshot isolation fails that lock with 1020)",
                ) {
                    val sessionUid = openSession()
                    val result =
                        inTx {
                            val loaded = repository.findSession(sessionUid = sessionUid).shouldNotBeNull()
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

                    then("H2 reads SUMMARIZED under the lock, not the managed instance's stale COLLECTING") {
                        result shouldBe AnswerRecordResult.SESSION_CLOSED
                        repository
                            .findSession(sessionUid = sessionUid)
                            .shouldNotBeNull()
                            .answers
                            .shouldBeEmpty()
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
                                    val session =
                                        repository
                                            .findSessionForSummary(
                                                sessionUid = sessionUid,
                                            ).shouldNotBeNull()
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
                        repository
                            .findSession(sessionUid = sessionUid)
                            .shouldNotBeNull()
                            .answers
                            .shouldBeEmpty()
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
                    val summaryRead =
                        inTx { repository.findSessionForSummary(sessionUid = sessionUid).shouldNotBeNull() }
                    answer.join()

                    then("the summary read waits for the answer's commit and includes it") {
                        answerResult.get() shouldBe AnswerRecordResult.RECORDED
                        summaryRead.answers.map { it.userId } shouldContainExactly listOf("U_A")
                    }
                }
            }

            given("recordSkip") {
                `when`("a member skips a collecting session and clicks Skip again later") {
                    val sessionUid = openSession()
                    val first = skip(sessionUid = sessionUid, skippedAt = cutoffAt.minusSeconds(600L))
                    val second = skip(sessionUid = sessionUid, skippedAt = cutoffAt.minusSeconds(300L))
                    val nudgeCandidate =
                        inTx {
                            repository
                                .findCollectingSessionsForNudge(
                                    now = cutoffAt.minusSeconds(60L),
                                    nudgeWindowEnd = cutoffAt,
                                ).single { it.sessionUid == sessionUid }
                        }

                    then("both are recorded, the first skip time is kept, and the nudge sees the member as skipped") {
                        first shouldBe AnswerRecordResult.RECORDED
                        second shouldBe AnswerRecordResult.RECORDED
                        val dispatch =
                            repository
                                .findSession(
                                    sessionUid = sessionUid,
                                ).shouldNotBeNull()
                                .dispatches
                                .single()
                        dispatch.skippedAt shouldBe cutoffAt.minusSeconds(600L)
                        dispatch.dmStatus shouldBe DispatchStatus.PENDING
                        repository
                            .findSession(sessionUid = sessionUid)
                            .shouldNotBeNull()
                            .answers
                            .shouldBeEmpty()
                        nudgeCandidate.skippedUserIds shouldBe setOf("U_A")
                    }
                }

                `when`("the session was already summarized") {
                    val sessionUid = openSession(status = SessionStatus.SUMMARIZED, summaryMessageTs = "outbox:1")
                    val result = skip(sessionUid = sessionUid, skippedAt = cutoffAt.minusSeconds(60L))

                    then("it is rejected as closed and the dispatch keeps no skip") {
                        result shouldBe AnswerRecordResult.SESSION_CLOSED
                        repository
                            .findSession(sessionUid = sessionUid)!!
                            .dispatches
                            .single()
                            .skippedAt shouldBe null
                    }
                }

                `when`("the session is still COLLECTING but the skip arrives at the cutoff") {
                    val sessionUid = openSession()
                    val result = skip(sessionUid = sessionUid, skippedAt = cutoffAt)

                    then("it is rejected as closed, since the summary may already be reading the session") {
                        result shouldBe AnswerRecordResult.SESSION_CLOSED
                        repository
                            .findSession(sessionUid = sessionUid)!!
                            .dispatches
                            .single()
                            .skippedAt shouldBe null
                    }
                }

                `when`("a member skips and then submits an answer") {
                    val sessionUid = openSession()
                    val skipped = skip(sessionUid = sessionUid, skippedAt = cutoffAt.minusSeconds(600L))
                    val answered =
                        record(
                            sessionUid = sessionUid,
                            responses = listOf("changed my mind"),
                            submittedAt = cutoffAt.minusSeconds(300L),
                        )
                    val session = repository.findSession(sessionUid = sessionUid).shouldNotBeNull()
                    val nudgeCandidate =
                        inTx {
                            repository
                                .findCollectingSessionsForNudge(
                                    now = cutoffAt.minusSeconds(60L),
                                    nudgeWindowEnd = cutoffAt,
                                ).single { it.sessionUid == sessionUid }
                        }

                    then("the answer is stored and the skip time is kept, so the member counts as both") {
                        skipped shouldBe AnswerRecordResult.RECORDED
                        answered shouldBe AnswerRecordResult.RECORDED
                        session.answers.single().responses shouldContainExactly listOf("changed my mind")
                        session.dispatches.single().skippedAt shouldBe cutoffAt.minusSeconds(600L)
                        nudgeCandidate.answeredUserIds shouldBe setOf("U_A")
                        nudgeCandidate.skippedUserIds shouldBe setOf("U_A")
                    }
                }

                `when`("a member answers and then clicks Skip") {
                    val sessionUid = openSession()
                    val answered =
                        record(
                            sessionUid = sessionUid,
                            responses = listOf("done"),
                            submittedAt = cutoffAt.minusSeconds(600L),
                        )
                    val skipped = skip(sessionUid = sessionUid, skippedAt = cutoffAt.minusSeconds(300L))
                    val session = repository.findSession(sessionUid = sessionUid).shouldNotBeNull()

                    then("the answer is untouched and the skip time is recorded") {
                        answered shouldBe AnswerRecordResult.RECORDED
                        skipped shouldBe AnswerRecordResult.RECORDED
                        session.answers.single().responses shouldContainExactly listOf("done")
                        session.dispatches.single().skippedAt shouldBe cutoffAt.minusSeconds(300L)
                    }
                }

                `when`("the summary holds the session lock while a skip arrives") {
                    val sessionUid = openSession()
                    val outcome =
                        withHeldLock(
                            holder = { hold ->
                                val read = repository.findSessionForSummary(sessionUid = sessionUid).shouldNotBeNull()
                                val marked =
                                    repository.markSessionSummarized(sessionId = read.sessionId, messageTs = "outbox:1")
                                hold()
                                read to marked
                            },
                            waiter = {
                                repository.recordSkip(
                                    sessionUid = sessionUid,
                                    userId = "U_A",
                                    skippedAt = cutoffAt.minusSeconds(60L),
                                )
                            },
                        )
                    val session = repository.findSession(sessionUid = sessionUid).shouldNotBeNull()

                    then("the skip waits, is rejected as closed, and neither the summary read nor the row has it") {
                        outcome.waiterBlockedWhileHeld shouldBe true
                        outcome.held.second shouldBe true
                        outcome.held.first.dispatches
                            .single()
                            .skippedAt shouldBe null
                        outcome.waited shouldBe AnswerRecordResult.SESSION_CLOSED
                        session.status shouldBe SessionStatus.SUMMARIZED
                        session.dispatches.single().skippedAt shouldBe null
                    }
                }

                `when`("a skip holds the session lock while the summary reads the session") {
                    val sessionUid = openSession()
                    val outcome =
                        withHeldLock(
                            holder = { hold ->
                                repository
                                    .recordSkip(
                                        sessionUid = sessionUid,
                                        userId = "U_A",
                                        skippedAt = cutoffAt.minusSeconds(60L),
                                    ).also { hold() }
                            },
                            waiter = {
                                val read = repository.findSessionForSummary(sessionUid = sessionUid).shouldNotBeNull()
                                read to
                                    repository.markSessionSummarized(sessionId = read.sessionId, messageTs = "outbox:1")
                            },
                        )
                    val session = repository.findSession(sessionUid = sessionUid).shouldNotBeNull()

                    then("the summary waits for the skip's commit, includes it, and the row ends summarized with it") {
                        outcome.waiterBlockedWhileHeld shouldBe true
                        outcome.held shouldBe AnswerRecordResult.RECORDED
                        outcome.waited.first.dispatches
                            .single()
                            .skippedAt shouldBe cutoffAt.minusSeconds(60L)
                        outcome.waited.second shouldBe true
                        session.status shouldBe SessionStatus.SUMMARIZED
                        session.dispatches.single().skippedAt shouldBe cutoffAt.minusSeconds(60L)
                    }
                }

                `when`("the session uid is unknown") {
                    val result = skip(sessionUid = UUID.randomUUID(), skippedAt = cutoffAt.minusSeconds(60L))

                    then("it reports SESSION_NOT_FOUND") {
                        result shouldBe AnswerRecordResult.SESSION_NOT_FOUND
                    }
                }
            }

            given("findLockedNudgeRecipients") {
                fun sentDispatch(userId: String) =
                    createSessionDispatch(
                        userId = userId,
                        dmSentAt = cutoffAt.minusSeconds(3_600L),
                        dmStatus = DispatchStatus.SENT,
                    )

                `when`("a session has silent, answered and skipped members plus one whose DM is not sent yet") {
                    val sessionUid =
                        openSession(
                            dispatches =
                                listOf(
                                    sentDispatch(userId = "U_SILENT"),
                                    sentDispatch(userId = "U_ANSWERED"),
                                    sentDispatch(userId = "U_SKIPPED"),
                                    createSessionDispatch(userId = "U_PENDING"),
                                ),
                        )
                    inTx {
                        repository.recordAnswer(
                            sessionUid = sessionUid,
                            userId = "U_ANSWERED",
                            responses = listOf("done"),
                            submittedAt = cutoffAt.minusSeconds(600L),
                        )
                    }
                    inTx {
                        repository.recordSkip(
                            sessionUid = sessionUid,
                            userId = "U_SKIPPED",
                            skippedAt = cutoffAt.minusSeconds(600L),
                        )
                    }
                    val sessionId = repository.findSession(sessionUid = sessionUid).shouldNotBeNull().sessionId
                    val (claimed, recipients) =
                        inTx {
                            repository.claimNudge(sessionId = sessionId) to
                                repository.findLockedNudgeRecipients(sessionUid = sessionUid)
                        }

                    then("the read under the claim's lock reports the sent, answered and skipped members") {
                        claimed shouldBe true
                        recipients.sessionId shouldBe sessionId
                        recipients.sentMemberIds shouldBe setOf("U_SILENT", "U_ANSWERED", "U_SKIPPED")
                        recipients.answeredUserIds shouldBe setOf("U_ANSWERED")
                        recipients.skippedUserIds shouldBe setOf("U_SKIPPED")
                    }
                }

                `when`("an answer holds the session lock while the nudge claims and re-reads") {
                    val sessionUid =
                        openSession(
                            dispatches =
                                listOf(
                                    sentDispatch(userId = "U_A"),
                                    sentDispatch(userId = "U_B"),
                                ),
                        )
                    val sessionId = repository.findSession(sessionUid = sessionUid).shouldNotBeNull().sessionId
                    val outcome =
                        withHeldLock(
                            holder = { hold ->
                                repository
                                    .recordAnswer(
                                        sessionUid = sessionUid,
                                        userId = "U_A",
                                        responses = listOf("made it"),
                                        submittedAt = cutoffAt.minusSeconds(60L),
                                    ).also { hold() }
                            },
                            waiter = {
                                repository.claimNudge(sessionId = sessionId) to
                                    repository.findLockedNudgeRecipients(sessionUid = sessionUid)
                            },
                        )

                    then(
                        "the claim is blocked until the answer commits and the re-read counts the member as answered",
                    ) {
                        outcome.waiterBlockedWhileHeld shouldBe true
                        outcome.held shouldBe AnswerRecordResult.RECORDED
                        outcome.waited.first shouldBe true
                        outcome.waited.second.answeredUserIds shouldBe setOf("U_A")
                    }
                }

                `when`("the session uid is unknown") {
                    then("it throws instead of reporting nobody to nudge") {
                        shouldThrow<DatabaseException> {
                            inTx { repository.findLockedNudgeRecipients(sessionUid = UUID.randomUUID()) }
                        }
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
                        val dispatch =
                            repository
                                .findSession(
                                    sessionUid = sessionUid,
                                ).shouldNotBeNull()
                                .dispatches
                                .single()
                        dispatch.dmStatus shouldBe DispatchStatus.FAILED
                        dispatch.failureReason shouldBe "skipped: closed"
                        repository
                            .findPendingDispatchesBefore(before = Instant.parse("2100-01-01T00:00:00Z"), limit = 500)
                            .map { it.dispatch.id } shouldNotContain dispatchId
                    }
                }
            }
        })
