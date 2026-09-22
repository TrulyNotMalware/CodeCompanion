package dev.notypie.repository.outbox

import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.schema.createOutboxMessage
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
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
            // updated_at is @UpdateTimestamp, so it is pushed back with plain SQL after the insert to simulate age.
            fun ageRow(eventId: String, updatedAt: LocalDateTime) =
                jdbcTemplate.update("UPDATE outbox_message SET updated_at = ? WHERE event_id = ?", updatedAt, eventId)

            given("claimPending") {
                `when`("two callers claim the same PENDING row") {
                    val row = repository.save(createOutboxMessage())

                    val first = repository.claimPending(eventIds = listOf(row.eventId))
                    val second = repository.claimPending(eventIds = listOf(row.eventId))

                    then("only the first wins and the row is IN_PROGRESS") {
                        first shouldBe 1
                        second shouldBe 0
                        repository.findById(row.eventId).get().status shouldBe MessageStatus.IN_PROGRESS.name
                    }
                }
            }

            given("findPendingMessages") {
                `when`("rows of mixed status exist") {
                    val older = repository.save(createOutboxMessage(createdAt = LocalDateTime.now().minusMinutes(5L)))
                    val newer = repository.save(createOutboxMessage(createdAt = LocalDateTime.now()))
                    repository.save(createOutboxMessage(status = MessageStatus.SUCCESS))

                    then("only PENDING rows come back, oldest first, capped by the limit") {
                        repository.findPendingMessages(limit = 10).map { it.eventId } shouldContainExactly
                            listOf(older.eventId, newer.eventId)
                        repository.findPendingMessages(limit = 1).map { it.eventId } shouldContainExactly
                            listOf(older.eventId)
                    }
                }
            }

            given("reclaimStuck") {
                `when`("an IN_PROGRESS row is older than the threshold") {
                    val row = repository.save(createOutboxMessage(status = MessageStatus.IN_PROGRESS))
                    ageRow(eventId = row.eventId, updatedAt = LocalDateTime.now().minusMinutes(30L))
                    val cutoff = LocalDateTime.now().minusMinutes(5L)

                    val first = repository.reclaimStuck(eventId = row.eventId, olderThan = cutoff)
                    val second = repository.reclaimStuck(eventId = row.eventId, olderThan = cutoff)

                    then("exactly one recovering poller wins, because the first reclaim refreshed updated_at") {
                        first shouldBe 1
                        second shouldBe 0
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
