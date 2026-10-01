package dev.notypie.repository.outbox

import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.schema.createOutboxMessage
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

@DataJpaTest
@ApplyExtension(extensions = [SpringExtension::class])
class MessageOutboxRepositoryTest
    @Autowired
    constructor(
        private val repository: MessageOutboxRepository,
        private val jdbcTemplate: JdbcTemplate,
    ) : BehaviorSpec({
            val farFuture = LocalDateTime.of(2999, 1, 1, 0, 0, 0)

            fun ageRow(eventId: String, updatedAt: LocalDateTime) =
                jdbcTemplate.update("UPDATE outbox_message SET updated_at = ? WHERE event_id = ?", updatedAt, eventId)

            fun ageCreatedAt(eventId: String, createdAt: LocalDateTime) =
                jdbcTemplate.update("UPDATE outbox_message SET created_at = ? WHERE event_id = ?", createdAt, eventId)

            fun statusOf(eventId: String): String =
                jdbcTemplate.queryForObject(
                    "SELECT status FROM outbox_message WHERE event_id = ?",
                    String::class.java,
                    eventId,
                )!!

            fun attemptsOf(eventId: String): Int =
                jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM outbox_message WHERE event_id = ?",
                    Int::class.java,
                    eventId,
                )!!

            fun sendsOf(eventId: String): Int =
                jdbcTemplate.queryForObject(
                    "SELECT send_count FROM outbox_message WHERE event_id = ?",
                    Int::class.java,
                    eventId,
                )!!

            fun updatedAtOf(eventId: String): LocalDateTime =
                jdbcTemplate.queryForObject(
                    "SELECT updated_at FROM outbox_message WHERE event_id = ?",
                    LocalDateTime::class.java,
                    eventId,
                )!!

            given("a freshly built row with its application-assigned event id") {
                `when`("it is saved and read back") {
                    val row = createOutboxMessage()
                    val saved = repository.save(row)
                    val loadedIsNew = repository.findByIdOrNull(row.eventId)?.isNew
                    repository.deleteById(row.eventId)

                    then("it is persisted as new instead of merged, so no SELECT precedes the INSERT") {
                        saved shouldBeSameInstanceAs row
                        saved.isNew shouldBe false
                    }

                    then("a row loaded back from the database is not new") {
                        loadedIsNew shouldBe false
                    }
                }
            }

            given("findPendingMessages") {
                `when`("an older PENDING row was inserted after a newer one, next to a SUCCESS row") {
                    val newer = repository.save(createOutboxMessage())
                    val older = repository.save(createOutboxMessage())
                    ageCreatedAt(eventId = older.eventId, createdAt = LocalDateTime.now().minusMinutes(5L))
                    repository.save(createOutboxMessage(status = MessageStatus.SUCCESS))

                    then("only PENDING rows come back, by created_at rather than insertion order, capped by limit") {
                        repository.findPendingMessages(limit = 10).map { it.eventId } shouldContainExactly
                            listOf(older.eventId, newer.eventId)
                        repository.findPendingMessages(limit = 1).map { it.eventId } shouldContainExactly
                            listOf(older.eventId)
                    }
                }
            }

            given("claimPending") {
                `when`("two callers claim the same PENDING row with the attempt count they read") {
                    val row = repository.save(createOutboxMessage())
                    val claimedAt = LocalDateTime.of(2030, 6, 1, 12, 0, 0)

                    val first = repository.claimPending(eventId = row.eventId, attemptCount = 0, now = claimedAt)
                    val second = repository.claimPending(eventId = row.eventId, attemptCount = 0, now = claimedAt)

                    then("only the first wins; the row is IN_PROGRESS on attempt 1, stamped with the supplied time") {
                        first shouldBe 1
                        second shouldBe 0
                        statusOf(eventId = row.eventId) shouldBe MessageStatus.IN_PROGRESS.name
                        attemptsOf(eventId = row.eventId) shouldBe 1
                        updatedAtOf(eventId = row.eventId) shouldBe claimedAt
                    }
                }

                `when`("the caller's clock is far from the database clock") {
                    val row = repository.save(createOutboxMessage())
                    repository.claimPending(eventId = row.eventId, attemptCount = 0, now = farFuture)

                    val reclaimedAgainstRealTime =
                        repository.reclaimStuck(
                            eventId = row.eventId,
                            attemptCount = 1,
                            olderThan = LocalDateTime.now(),
                            now = LocalDateTime.now(),
                        )

                    then("updated_at holds the caller's value, so the stuck guard ages it by the caller's clock") {
                        updatedAtOf(eventId = row.eventId) shouldBe farFuture
                        reclaimedAgainstRealTime shouldBe 0
                    }
                }
            }

            given("reclaimStuck") {
                `when`("an IN_PROGRESS row on attempt 1 is older than the threshold") {
                    val row = repository.save(createOutboxMessage())
                    repository.claimPending(eventId = row.eventId, attemptCount = 0, now = LocalDateTime.now())
                    ageRow(eventId = row.eventId, updatedAt = LocalDateTime.now().minusMinutes(30L))
                    val cutoff = LocalDateTime.now().minusMinutes(5L)
                    val reclaimedAt = LocalDateTime.now().withNano(0)

                    val first =
                        repository.reclaimStuck(
                            eventId = row.eventId,
                            attemptCount = 1,
                            olderThan = cutoff,
                            now = reclaimedAt,
                        )
                    val second =
                        repository.reclaimStuck(
                            eventId = row.eventId,
                            attemptCount = 1,
                            olderThan = cutoff,
                            now = reclaimedAt,
                        )

                    then("exactly one recovering poller wins and the attempt count moves to 2") {
                        first shouldBe 1
                        second shouldBe 0
                        attemptsOf(eventId = row.eventId) shouldBe 2
                        updatedAtOf(eventId = row.eventId) shouldBe reclaimedAt
                    }
                }

                `when`("the sweep read an attempt count that another instance already moved past") {
                    val row = repository.save(createOutboxMessage())
                    repository.claimPending(eventId = row.eventId, attemptCount = 0, now = LocalDateTime.now())
                    ageRow(eventId = row.eventId, updatedAt = LocalDateTime.now().minusMinutes(30L))

                    val stale =
                        repository.reclaimStuck(
                            eventId = row.eventId,
                            attemptCount = 0,
                            olderThan = LocalDateTime.now().minusMinutes(5L),
                            now = LocalDateTime.now(),
                        )

                    then("the stale reclaim loses") {
                        stale shouldBe 0
                        attemptsOf(eventId = row.eventId) shouldBe 1
                    }
                }
            }

            given("renewClaim and completeClaim") {
                `when`("a worker queued on attempt 1 runs after the row was reclaimed as attempt 2 and finished") {
                    val row = repository.save(createOutboxMessage())
                    repository.claimPending(eventId = row.eventId, attemptCount = 0, now = LocalDateTime.now())
                    ageRow(eventId = row.eventId, updatedAt = LocalDateTime.now().minusMinutes(30L))
                    repository.reclaimStuck(
                        eventId = row.eventId,
                        attemptCount = 1,
                        olderThan = LocalDateTime.now().minusMinutes(5L),
                        now = LocalDateTime.now(),
                    )
                    val ownerRenewed =
                        repository.renewClaim(eventId = row.eventId, attemptCount = 2, now = LocalDateTime.now())
                    val ownerCompleted =
                        repository.completeClaim(
                            eventId = row.eventId,
                            attemptCount = 2,
                            status = MessageStatus.SUCCESS.name,
                            now = LocalDateTime.now(),
                        )

                    val lateRenewed =
                        repository.renewClaim(eventId = row.eventId, attemptCount = 1, now = LocalDateTime.now())
                    val lateCompleted =
                        repository.completeClaim(
                            eventId = row.eventId,
                            attemptCount = 1,
                            status = MessageStatus.FAILURE.name,
                            now = LocalDateTime.now(),
                        )

                    then("the current owner renews and finishes; the late worker can neither renew nor overwrite") {
                        ownerRenewed shouldBe 1
                        ownerCompleted shouldBe 1
                        lateRenewed shouldBe 0
                        lateCompleted shouldBe 0
                        statusOf(eventId = row.eventId) shouldBe MessageStatus.SUCCESS.name
                    }

                    then("only the renewal that led to a send counts against the send budget") {
                        sendsOf(eventId = row.eventId) shouldBe 1
                    }
                }
            }

            given("deferClaim") {
                `when`("the owner defers a rate-limited send and a stale worker tries the same") {
                    val row = repository.save(createOutboxMessage())
                    repository.claimPending(eventId = row.eventId, attemptCount = 0, now = LocalDateTime.now())
                    repository.renewClaim(eventId = row.eventId, attemptCount = 1, now = LocalDateTime.now())
                    val eligibleFrom = LocalDateTime.of(2030, 6, 1, 12, 0, 0)

                    val deferred =
                        repository.deferClaim(eventId = row.eventId, attemptCount = 1, updatedAt = eligibleFrom)
                    val stale =
                        repository.deferClaim(eventId = row.eventId, attemptCount = 0, updatedAt = LocalDateTime.now())

                    then("the owner's send is refunded and the row waits until its new updated_at ages out") {
                        deferred shouldBe 1
                        stale shouldBe 0
                        sendsOf(eventId = row.eventId) shouldBe 0
                        updatedAtOf(eventId = row.eventId) shouldBe eligibleFrom
                        statusOf(eventId = row.eventId) shouldBe MessageStatus.IN_PROGRESS.name
                    }
                }

                `when`("a row that never reached a send is deferred") {
                    val row = repository.save(createOutboxMessage())
                    repository.claimPending(eventId = row.eventId, attemptCount = 0, now = LocalDateTime.now())

                    repository.deferClaim(eventId = row.eventId, attemptCount = 1, updatedAt = LocalDateTime.now())

                    then("the send count does not go negative") {
                        sendsOf(eventId = row.eventId) shouldBe 0
                    }
                }
            }

            given("abandonStuck") {
                `when`("the owner renewed the row after the sweep read it as stuck") {
                    val row = repository.save(createOutboxMessage())
                    repository.claimPending(eventId = row.eventId, attemptCount = 0, now = LocalDateTime.now())
                    val cutoff = LocalDateTime.now().minusMinutes(5L)

                    val abandoned =
                        repository.abandonStuck(
                            eventId = row.eventId,
                            attemptCount = 1,
                            olderThan = cutoff,
                            now = LocalDateTime.now(),
                        )

                    then("the fresh claim is left alone") {
                        abandoned shouldBe 0
                        statusOf(eventId = row.eventId) shouldBe MessageStatus.IN_PROGRESS.name
                    }
                }

                `when`("the row is still in the state the sweep observed") {
                    val row = repository.save(createOutboxMessage())
                    repository.claimPending(eventId = row.eventId, attemptCount = 0, now = LocalDateTime.now())
                    ageRow(eventId = row.eventId, updatedAt = LocalDateTime.now().minusMinutes(30L))

                    val abandoned =
                        repository.abandonStuck(
                            eventId = row.eventId,
                            attemptCount = 1,
                            olderThan = LocalDateTime.now().minusMinutes(5L),
                            now = LocalDateTime.now(),
                        )

                    then("it becomes FAILURE") {
                        abandoned shouldBe 1
                        statusOf(eventId = row.eventId) shouldBe MessageStatus.FAILURE.name
                    }
                }

                `when`("the sweep's attempt token is stale because another sweep reclaimed the row") {
                    val row = repository.save(createOutboxMessage())
                    repository.claimPending(eventId = row.eventId, attemptCount = 0, now = LocalDateTime.now())
                    ageRow(eventId = row.eventId, updatedAt = LocalDateTime.now().minusMinutes(30L))
                    repository.reclaimStuck(
                        eventId = row.eventId,
                        attemptCount = 1,
                        olderThan = LocalDateTime.now().minusMinutes(5L),
                        now = LocalDateTime.now().minusMinutes(20L),
                    )

                    val abandoned =
                        repository.abandonStuck(
                            eventId = row.eventId,
                            attemptCount = 1,
                            olderThan = LocalDateTime.now().minusMinutes(5L),
                            now = LocalDateTime.now(),
                        )

                    then("the stale abandon affects no row and the new owner's claim survives") {
                        abandoned shouldBe 0
                        statusOf(eventId = row.eventId) shouldBe MessageStatus.IN_PROGRESS.name
                        attemptsOf(eventId = row.eventId) shouldBe 2
                    }
                }
            }

            given("countInProgressWithSendsAtLeast") {
                `when`("IN_PROGRESS rows sit on different send and attempt counts") {
                    val before = repository.countInProgressWithSendsAtLeast(sends = 3)
                    val retrying = repository.save(createOutboxMessage())
                    val reclaimedOnly = repository.save(createOutboxMessage())
                    listOf(retrying, reclaimedOnly).forEach {
                        repository.claimPending(eventId = it.eventId, attemptCount = 0, now = farFuture)
                    }
                    jdbcTemplate.update("UPDATE outbox_message SET send_count = 3 WHERE event_id = ?", retrying.eventId)
                    jdbcTemplate.update(
                        "UPDATE outbox_message SET attempt_count = 9 WHERE event_id = ?",
                        reclaimedOnly.eventId,
                    )

                    then("only rows whose sends reached the threshold are counted, however often they were claimed") {
                        repository.countInProgressWithSendsAtLeast(sends = 3) shouldBe before + 1L
                    }
                }
            }

            given("deleteTerminalOlderThan") {
                `when`("old terminal rows sit next to a PENDING one and a fresh terminal one") {
                    val oldSuccess = repository.save(createOutboxMessage(status = MessageStatus.SUCCESS))
                    val oldFailure = repository.save(createOutboxMessage(status = MessageStatus.FAILURE))
                    val oldPending = repository.save(createOutboxMessage(status = MessageStatus.PENDING))
                    val freshSuccess = repository.save(createOutboxMessage(status = MessageStatus.SUCCESS))
                    listOf(oldSuccess, oldFailure, oldPending).forEach {
                        ageRow(eventId = it.eventId, updatedAt = LocalDateTime.now().minusDays(30L))
                    }

                    val deleted =
                        repository.deleteTerminalOlderThan(
                            olderThan = LocalDateTime.now().minusDays(14L),
                            limit = 100,
                        )

                    then("only the aged SUCCESS/FAILURE rows are purged") {
                        deleted shouldBe 2
                        repository.existsById(oldPending.eventId) shouldBe true
                        repository.existsById(freshSuccess.eventId) shouldBe true
                        repository.existsById(oldSuccess.eventId) shouldBe false
                    }
                }
            }
        })
