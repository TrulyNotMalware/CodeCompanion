package dev.notypie.repository.agent

import dev.notypie.repository.agent.schema.AgentAskTurnSchema
import dev.notypie.repository.agent.schema.AgentAskTurnStatus
import dev.notypie.schema.createAgentAskTurnSchema
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeMonotonicallyDecreasing
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.UUID

private const val MARIADB_MODE_H2_URL =
    "jdbc:h2:mem:agent_ask_turn_mariadb;MODE=MariaDB;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"

@DataJpaTest(properties = ["spring.datasource.url=$MARIADB_MODE_H2_URL"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ApplyExtension(extensions = [SpringExtension::class])
class AgentAskTurnRepositoryTest
    @Autowired
    constructor(
        private val repository: AgentAskTurnRepository,
        private val entityManager: TestEntityManager,
        private val transactionManager: PlatformTransactionManager,
    ) : BehaviorSpec({
            val deliveredAt = LocalDateTime.of(2026, 10, 12, 9, 0, 0)
            val now = deliveredAt.plusMinutes(10L)

            fun stored(
                status: AgentAskTurnStatus = AgentAskTurnStatus.PENDING,
                attemptCount: Int = 0,
                runCount: Int = 0,
                createdAt: LocalDateTime = deliveredAt,
                updatedAt: LocalDateTime = createdAt,
            ): String {
                val row =
                    repository.saveAndFlush(
                        createAgentAskTurnSchema(
                            status = status,
                            attemptCount = attemptCount,
                            runCount = runCount,
                            createdAt = createdAt,
                            updatedAt = updatedAt,
                        ),
                    )
                entityManager.clear()
                return row.eventId
            }

            fun reloaded(eventId: String): AgentAskTurnSchema {
                entityManager.clear()
                return checkNotNull(repository.findByIdOrNull(id = eventId)) { "no ask turn $eventId" }
            }

            given("begin") {
                `when`("two runs begin the same PENDING turn with the attempt count they read") {
                    then("only the first wins; the turn is IN_PROGRESS on attempt 1, stamped with the caller's time") {
                        val eventId = stored()

                        repository.begin(eventId = eventId, attemptCount = 0, now = now) shouldBe 1
                        repository.begin(eventId = eventId, attemptCount = 0, now = now.plusSeconds(1L)) shouldBe 0

                        val turn = reloaded(eventId = eventId)
                        turn.status shouldBe AgentAskTurnStatus.IN_PROGRESS
                        turn.attemptCount shouldBe 1
                        turn.runCount shouldBe 1
                        turn.updatedAt shouldBe now
                        turn.createdAt shouldBe deliveredAt
                    }
                }

                `when`("the turn is already running") {
                    then("a run that read the current attempt count still cannot begin it") {
                        val eventId = stored(status = AgentAskTurnStatus.IN_PROGRESS, attemptCount = 1)

                        repository.begin(eventId = eventId, attemptCount = 1, now = now) shouldBe 0

                        reloaded(eventId = eventId).attemptCount shouldBe 1
                    }
                }

                `when`("the turn waits again after a run, and a run that read the older attempt count begins it") {
                    then("the stale count alone refuses it and the waiting turn is unchanged") {
                        val eventId = stored(attemptCount = 1, runCount = 1)

                        repository.begin(eventId = eventId, attemptCount = 0, now = now) shouldBe 0

                        val turn = reloaded(eventId = eventId)
                        turn.status shouldBe AgentAskTurnStatus.PENDING
                        turn.attemptCount shouldBe 1
                        turn.runCount shouldBe 1
                        turn.updatedAt shouldBe deliveredAt
                    }
                }
            }

            given("release") {
                `when`("the owner gives a busy turn back") {
                    then("the turn is PENDING again on the same attempt with that run taken back") {
                        val eventId = stored(status = AgentAskTurnStatus.IN_PROGRESS, attemptCount = 3, runCount = 2)

                        repository.release(eventId = eventId, attemptCount = 3, now = now) shouldBe 1

                        val turn = reloaded(eventId = eventId)
                        turn.status shouldBe AgentAskTurnStatus.PENDING
                        turn.attemptCount shouldBe 3
                        turn.runCount shouldBe 1
                        turn.updatedAt shouldBe now
                    }
                }

                `when`("the turn's run count is already zero") {
                    then("the give-back leaves it at zero") {
                        val eventId = stored(status = AgentAskTurnStatus.IN_PROGRESS, attemptCount = 1, runCount = 0)

                        repository.release(eventId = eventId, attemptCount = 1, now = now) shouldBe 1

                        reloaded(eventId = eventId).runCount shouldBe 0
                    }
                }

                `when`("the turn is not running, although the attempt count matches") {
                    then("a waiting turn keeps its run and a completed turn stays completed") {
                        val waiting = stored(attemptCount = 1, runCount = 1)
                        val completed = stored(status = AgentAskTurnStatus.COMPLETED, attemptCount = 1, runCount = 1)

                        repository.release(eventId = waiting, attemptCount = 1, now = now) shouldBe 0
                        repository.release(eventId = completed, attemptCount = 1, now = now) shouldBe 0

                        val stillWaiting = reloaded(eventId = waiting)
                        stillWaiting.status shouldBe AgentAskTurnStatus.PENDING
                        stillWaiting.runCount shouldBe 1
                        stillWaiting.updatedAt shouldBe deliveredAt
                        val stillCompleted = reloaded(eventId = completed)
                        stillCompleted.status shouldBe AgentAskTurnStatus.COMPLETED
                        stillCompleted.runCount shouldBe 1
                        stillCompleted.updatedAt shouldBe deliveredAt
                    }
                }

                `when`("a run whose attempt was superseded gives the turn back") {
                    then("the newer run keeps the turn and its run stays counted") {
                        val eventId = stored(status = AgentAskTurnStatus.IN_PROGRESS, attemptCount = 2, runCount = 2)

                        repository.release(eventId = eventId, attemptCount = 1, now = now) shouldBe 0

                        val turn = reloaded(eventId = eventId)
                        turn.status shouldBe AgentAskTurnStatus.IN_PROGRESS
                        turn.runCount shouldBe 2
                        turn.updatedAt shouldBe deliveredAt
                    }
                }
            }

            given("releaseOrphan") {
                `when`("an IN_PROGRESS turn was last touched before the cutoff") {
                    then("it is PENDING again with both counters as they were, stamped with the caller's time") {
                        val eventId = stored(status = AgentAskTurnStatus.IN_PROGRESS, attemptCount = 1, runCount = 1)

                        repository.releaseOrphan(
                            eventId = eventId,
                            attemptCount = 1,
                            orphanedBefore = deliveredAt.plusSeconds(1L),
                            now = now,
                        ) shouldBe 1

                        val turn = reloaded(eventId = eventId)
                        turn.status shouldBe AgentAskTurnStatus.PENDING
                        turn.attemptCount shouldBe 1
                        turn.runCount shouldBe 1
                        turn.updatedAt shouldBe now
                    }
                }

                `when`("the turn is not running, although its attempt count matches and it is old enough") {
                    then("a waiting turn is not touched and a completed turn stays completed") {
                        val waiting = stored(attemptCount = 1)
                        val completed = stored(status = AgentAskTurnStatus.COMPLETED, attemptCount = 1)

                        repository.releaseOrphan(
                            eventId = waiting,
                            attemptCount = 1,
                            orphanedBefore = now,
                            now = now,
                        ) shouldBe 0
                        repository.releaseOrphan(
                            eventId = completed,
                            attemptCount = 1,
                            orphanedBefore = now,
                            now = now,
                        ) shouldBe 0

                        reloaded(eventId = waiting).updatedAt shouldBe deliveredAt
                        val stillCompleted = reloaded(eventId = completed)
                        stillCompleted.status shouldBe AgentAskTurnStatus.COMPLETED
                        stillCompleted.updatedAt shouldBe deliveredAt
                    }
                }

                `when`("the turn was touched at the cutoff, or another run began it since it was read") {
                    then("it stays with its owner") {
                        val live = stored(status = AgentAskTurnStatus.IN_PROGRESS, attemptCount = 1)
                        val retaken = stored(status = AgentAskTurnStatus.IN_PROGRESS, attemptCount = 2)

                        repository.releaseOrphan(
                            eventId = live,
                            attemptCount = 1,
                            orphanedBefore = deliveredAt,
                            now = now,
                        ) shouldBe 0
                        repository.releaseOrphan(
                            eventId = retaken,
                            attemptCount = 1,
                            orphanedBefore = now,
                            now = now,
                        ) shouldBe 0

                        reloaded(eventId = live).status shouldBe AgentAskTurnStatus.IN_PROGRESS
                        reloaded(eventId = retaken).status shouldBe AgentAskTurnStatus.IN_PROGRESS
                    }
                }
            }

            given("finish") {
                `when`("the owner of a running turn settles it") {
                    then("the turn is COMPLETED with both counters as they were, stamped with the caller's time") {
                        val eventId = stored(status = AgentAskTurnStatus.IN_PROGRESS, attemptCount = 1, runCount = 1)

                        repository.finish(
                            eventId = eventId,
                            attemptCount = 1,
                            status = AgentAskTurnStatus.COMPLETED.name,
                            now = now,
                        ) shouldBe 1

                        val turn = reloaded(eventId = eventId)
                        turn.status shouldBe AgentAskTurnStatus.COMPLETED
                        turn.attemptCount shouldBe 1
                        turn.runCount shouldBe 1
                        turn.updatedAt shouldBe now
                    }
                }

                `when`("the owner was released as an orphan and nobody began the turn again") {
                    then("it still settles the turn") {
                        val eventId = stored(status = AgentAskTurnStatus.PENDING, attemptCount = 1)

                        repository.finish(
                            eventId = eventId,
                            attemptCount = 1,
                            status = AgentAskTurnStatus.COMPLETED.name,
                            now = now,
                        ) shouldBe 1

                        reloaded(eventId = eventId).status shouldBe AgentAskTurnStatus.COMPLETED
                    }
                }

                `when`("another run began the turn after the owner was released as an orphan") {
                    then("the stale owner settles nothing") {
                        val eventId = stored(status = AgentAskTurnStatus.IN_PROGRESS, attemptCount = 2)

                        repository.finish(
                            eventId = eventId,
                            attemptCount = 1,
                            status = AgentAskTurnStatus.COMPLETED.name,
                            now = now,
                        ) shouldBe 0

                        val turn = reloaded(eventId = eventId)
                        turn.status shouldBe AgentAskTurnStatus.IN_PROGRESS
                        turn.updatedAt shouldBe deliveredAt
                    }
                }

                `when`("a turn that never ran is given up") {
                    then("it is ABANDONED") {
                        val eventId = stored()

                        repository.finish(
                            eventId = eventId,
                            attemptCount = 0,
                            status = AgentAskTurnStatus.ABANDONED.name,
                            now = now,
                        ) shouldBe 1

                        reloaded(eventId = eventId).status shouldBe AgentAskTurnStatus.ABANDONED
                    }
                }

                `when`("the turn already reached an outcome") {
                    then("neither outcome is overwritten") {
                        val completed = stored(status = AgentAskTurnStatus.COMPLETED, attemptCount = 1)
                        val abandoned = stored(status = AgentAskTurnStatus.ABANDONED, attemptCount = 1)

                        repository.finish(
                            eventId = completed,
                            attemptCount = 1,
                            status = AgentAskTurnStatus.ABANDONED.name,
                            now = now,
                        ) shouldBe 0
                        repository.finish(
                            eventId = abandoned,
                            attemptCount = 1,
                            status = AgentAskTurnStatus.COMPLETED.name,
                            now = now,
                        ) shouldBe 0

                        reloaded(eventId = completed).status shouldBe AgentAskTurnStatus.COMPLETED
                        reloaded(eventId = abandoned).status shouldBe AgentAskTurnStatus.ABANDONED
                    }
                }
            }

            given("a turn that is begun, given back, begun again, released as an orphan and settled") {
                `when`("each transition is applied in turn") {
                    then("only begin raises the attempt count, and only begin and the give-back move the run count") {
                        val eventId = stored()
                        val counts = mutableListOf<Pair<Int, Int>>()

                        fun record() {
                            counts += reloaded(eventId = eventId).let { it.attemptCount to it.runCount }
                        }

                        repository.begin(eventId = eventId, attemptCount = 0, now = now)
                        record()
                        repository.release(eventId = eventId, attemptCount = 1, now = now)
                        record()
                        repository.begin(eventId = eventId, attemptCount = 1, now = now)
                        record()
                        repository.releaseOrphan(
                            eventId = eventId,
                            attemptCount = 2,
                            orphanedBefore = now.plusSeconds(1L),
                            now = now,
                        )
                        record()
                        repository.finish(
                            eventId = eventId,
                            attemptCount = 2,
                            status = AgentAskTurnStatus.COMPLETED.name,
                            now = now,
                        )
                        record()

                        counts shouldContainExactly listOf(1 to 1, 1 to 0, 2 to 1, 2 to 1, 2 to 1)
                        reloaded(eventId = eventId).status shouldBe AgentAskTurnStatus.COMPLETED
                    }
                }
            }

            given("recordDelivered") {
                `when`("a question is recorded for the first time") {
                    val eventId = UUID.randomUUID().toString()
                    val recorded = repository.recordDelivered(eventId = eventId, messageTs = "1.1", now = deliveredAt)
                    val stored = repository.findByIdOrNull(id = eventId)
                    repository.deleteById(eventId)

                    then("it reports the row as stored now: PENDING with that ts, no attempts, the caller's time") {
                        recorded.storedNow shouldBe true
                        recorded.turn.messageTs shouldBe "1.1"
                        val turn = stored.shouldNotBeNull()
                        turn.messageTs shouldBe "1.1"
                        turn.status shouldBe AgentAskTurnStatus.PENDING
                        turn.attemptCount shouldBe 0
                        turn.runCount shouldBe 0
                        turn.createdAt shouldBe deliveredAt
                        turn.updatedAt shouldBe deliveredAt
                    }
                }

                `when`("the question is recorded again, with another ts, while its turn still waits") {
                    val eventId = UUID.randomUUID().toString()
                    repository.recordDelivered(eventId = eventId, messageTs = "1.1", now = deliveredAt)
                    val again =
                        repository.recordDelivered(eventId = eventId, messageTs = "2.2", now = now.plusMinutes(1L))
                    repository.deleteById(eventId)

                    then("it reports the row as found, not stored now: still PENDING with the first ts and time") {
                        again.storedNow shouldBe false
                        again.turn.messageTs shouldBe "1.1"
                        again.turn.status shouldBe AgentAskTurnStatus.PENDING
                        again.turn.attemptCount shouldBe 0
                        again.turn.createdAt shouldBe deliveredAt
                        again.turn.updatedAt shouldBe deliveredAt
                    }
                }

                `when`("the question is recorded again, with another ts, after its turn began") {
                    val eventId = UUID.randomUUID().toString()
                    repository.recordDelivered(eventId = eventId, messageTs = "1.1", now = deliveredAt)
                    repository.begin(eventId = eventId, attemptCount = 0, now = now)
                    val again =
                        repository.recordDelivered(eventId = eventId, messageTs = "2.2", now = now.plusMinutes(1L))
                    repository.deleteById(eventId)

                    then("it reports the row as found: the first ts with the turn's current state") {
                        again.storedNow shouldBe false
                        again.turn.messageTs shouldBe "1.1"
                        again.turn.status shouldBe AgentAskTurnStatus.IN_PROGRESS
                        again.turn.attemptCount shouldBe 1
                        again.turn.runCount shouldBe 1
                        again.turn.createdAt shouldBe deliveredAt
                        again.turn.updatedAt shouldBe now
                    }
                }

                `when`("the insert is refused for a reason other than an existing row") {
                    val eventId = UUID.randomUUID().toString()
                    val refusal =
                        runCatching {
                            repository.recordDelivered(
                                eventId = eventId,
                                messageTs = "9".repeat(n = 33),
                                now = deliveredAt,
                            )
                        }.exceptionOrNull()
                    val stored = repository.existsById(eventId)

                    then("the violation is rethrown and nothing is stored") {
                        refusal.shouldBeInstanceOf<DataIntegrityViolationException>()
                        stored shouldBe false
                    }
                }

                `when`("it is called inside a transaction") {
                    val eventId = UUID.randomUUID().toString()
                    val refusal =
                        runCatching {
                            TransactionTemplate(transactionManager).execute {
                                repository.recordDelivered(eventId = eventId, messageTs = "1.1", now = deliveredAt)
                            }
                        }.exceptionOrNull()
                    val stored = repository.existsById(eventId)
                    repository.deleteById(eventId)

                    then("it refuses to run and nothing is stored") {
                        refusal.shouldBeInstanceOf<IllegalStateException>()
                        stored shouldBe false
                    }
                }
            }

            given("findResumable") {
                `when`("turns of every status and age are stored") {
                    then("only waiting turns and orphaned runs come back, least recently touched first, up to limit") {
                        val pendingBefore = now.minusSeconds(60L)
                        val orphanedBefore = now.minusSeconds(180L)
                        val orphaned =
                            stored(
                                status = AgentAskTurnStatus.IN_PROGRESS,
                                attemptCount = 3,
                                updatedAt = orphanedBefore.minusSeconds(1L),
                            )
                        val waiting = stored(updatedAt = pendingBefore.minusSeconds(1L))
                        stored(updatedAt = pendingBefore)
                        stored(status = AgentAskTurnStatus.IN_PROGRESS, updatedAt = orphanedBefore)
                        stored(status = AgentAskTurnStatus.COMPLETED, updatedAt = deliveredAt)
                        stored(status = AgentAskTurnStatus.ABANDONED, updatedAt = deliveredAt)

                        val resumable =
                            repository.findResumable(
                                pendingBefore = pendingBefore,
                                orphanedBefore = orphanedBefore,
                                limit = 10,
                            )
                        val firstOnly =
                            repository.findResumable(
                                pendingBefore = pendingBefore,
                                orphanedBefore = orphanedBefore,
                                limit = 1,
                            )

                        resumable.map { it.eventId } shouldContainExactly listOf(orphaned, waiting)
                        resumable.map { it.status } shouldContainExactly
                            listOf(AgentAskTurnStatus.IN_PROGRESS, AgentAskTurnStatus.PENDING)
                        resumable.map { it.attemptCount } shouldContainExactly listOf(3, 0)
                        firstOnly.map { it.eventId } shouldContainExactly listOf(orphaned)
                    }
                }
            }

            given("deleteFinishedOlderThan") {
                val cutoff = now.minusDays(14L)

                `when`("turns of every status sit on both sides of the cutoff") {
                    then("only COMPLETED/ABANDONED turns touched before it go; older waiting and running turns stay") {
                        val completed =
                            stored(status = AgentAskTurnStatus.COMPLETED, updatedAt = cutoff.minusNanos(1_000L))
                        val abandoned = stored(status = AgentAskTurnStatus.ABANDONED, updatedAt = cutoff.minusDays(30L))
                        val kept =
                            listOf(
                                stored(status = AgentAskTurnStatus.PENDING, updatedAt = cutoff.minusDays(30L)),
                                stored(status = AgentAskTurnStatus.IN_PROGRESS, updatedAt = cutoff.minusDays(30L)),
                                stored(status = AgentAskTurnStatus.COMPLETED, updatedAt = cutoff),
                                stored(status = AgentAskTurnStatus.ABANDONED, updatedAt = cutoff.plusSeconds(1L)),
                            )

                        repository.deleteFinishedOlderThan(olderThan = cutoff, limit = 10) shouldBe 2

                        repository.existsById(completed) shouldBe false
                        repository.existsById(abandoned) shouldBe false
                        kept.map { repository.existsById(it) } shouldContainExactly List(size = kept.size) { true }
                    }
                }

                `when`("one turn of every status sits past the cutoff") {
                    then("exactly the statuses no transition leaves are deleted") {
                        fun finished(status: AgentAskTurnStatus): Boolean =
                            when (status) {
                                AgentAskTurnStatus.PENDING, AgentAskTurnStatus.IN_PROGRESS -> false
                                AgentAskTurnStatus.COMPLETED, AgentAskTurnStatus.ABANDONED -> true
                            }
                        val pastCutoff = cutoff.minusDays(1L)
                        val turnByStatus =
                            AgentAskTurnStatus.entries.associateWith { stored(status = it, updatedAt = pastCutoff) }

                        repository.deleteFinishedOlderThan(olderThan = cutoff, limit = 10)

                        turnByStatus.filterValues { repository.existsById(it) }.keys shouldBe
                            AgentAskTurnStatus.entries.filterNot { finished(status = it) }.toSet()
                    }
                }

                `when`("more finished turns are past the cutoff than the limit") {
                    then("one call deletes exactly limit rows and the next call the rest") {
                        val pastCutoff = cutoff.minusDays(1L)
                        val finished =
                            List(size = 3) { stored(status = AgentAskTurnStatus.COMPLETED, updatedAt = pastCutoff) }

                        repository.deleteFinishedOlderThan(olderThan = cutoff, limit = 2) shouldBe 2
                        finished.count { repository.existsById(it) } shouldBe 1
                        repository.deleteFinishedOlderThan(olderThan = cutoff, limit = 2)
                        finished.count { repository.existsById(it) } shouldBe 0
                    }
                }

                `when`("COMPLETED and ABANDONED turns past the cutoff outnumber the limit") {
                    then("the first call deletes limit rows across both statuses and later calls the rest") {
                        val pastCutoff = cutoff.minusDays(1L)
                        val finished =
                            List(size = 3) { stored(status = AgentAskTurnStatus.COMPLETED, updatedAt = pastCutoff) } +
                                List(size = 3) { stored(status = AgentAskTurnStatus.ABANDONED, updatedAt = pastCutoff) }
                        val waiting = stored(status = AgentAskTurnStatus.PENDING, updatedAt = pastCutoff)

                        repository.deleteFinishedOlderThan(olderThan = cutoff, limit = 2) shouldBe 2
                        finished.count { repository.existsById(it) } shouldBe 4
                        val later =
                            List(size = 3) {
                                repository.deleteFinishedOlderThan(olderThan = cutoff, limit = 2) to
                                    finished.count { repository.existsById(it) }
                            }

                        later.map { (reported, _) -> reported }.filterNot { it in 0..2 }.shouldBeEmpty()
                        (listOf(4) + later.map { (_, left) -> left }).shouldBeMonotonicallyDecreasing()
                        later.last().second shouldBe 0
                        repository.existsById(waiting) shouldBe true
                    }
                }
            }
        })
