package dev.notypie.application.service.calendar

import ch.qos.logback.classic.Level
import dev.notypie.application.outbox.DEFAULT_TEST_NOW
import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.service.relay.RETENTION_MAX_BATCHES_PER_TICK
import dev.notypie.logging.captureLogs
import dev.notypie.logging.messagesAt
import dev.notypie.repository.calendar.GoogleOAuthStateRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.ZoneOffset

private class StateRetentionLane(
    deletes: List<Int>,
) {
    val stateRepository =
        mockk<GoogleOAuthStateRepository> {
            every { deleteExpired(before = any(), limit = any()) } returnsMany deletes
        }
    val scheduler = GoogleOAuthStateRetentionScheduler(stateRepository = stateRepository, clock = createFixedUtcClock())
}

class GoogleOAuthStateRetentionSchedulerTest :
    BehaviorSpec({
        val loggerName = GoogleOAuthStateRetentionScheduler::class.java.packageName
        val now = DEFAULT_TEST_NOW.toInstant(ZoneOffset.UTC)
        val batch = 1000

        given("expired OAuth states, purged in batches of 1000 against the clock's current instant") {
            `when`("the third batch comes back short") {
                val lane = StateRetentionLane(deletes = listOf(batch, batch, 7))
                val (purged, logs) = captureLogs(loggerName = loggerName) { lane.scheduler.purgeOnce() }

                then("batches run until a short one, each against the current instant, and the sum is returned") {
                    purged shouldBe 2007
                    verify(exactly = 3) { lane.stateRepository.deleteExpired(before = now, limit = batch) }
                }

                then("one INFO line names the table and the total, and nothing is logged at ERROR") {
                    logs.messagesAt(level = Level.INFO) shouldBe
                        listOf("OAuth state retention purged 2007 rows of google_oauth_state expired before $now")
                    logs.messagesAt(level = Level.ERROR).shouldBeEmpty()
                }
            }

            `when`("every batch comes back full") {
                val lane = StateRetentionLane(deletes = List(size = 30) { batch })
                val purged = lane.scheduler.purgeOnce()

                then("the tick stops after the per-tick batch cap and the rest waits for the next tick") {
                    purged shouldBe RETENTION_MAX_BATCHES_PER_TICK * batch
                    verify(exactly = RETENTION_MAX_BATCHES_PER_TICK) {
                        lane.stateRepository.deleteExpired(before = any(), limit = any())
                    }
                }
            }

            `when`("the second batch throws inside the scheduled tick") {
                val lane = StateRetentionLane(deletes = listOf(batch))
                every { lane.stateRepository.deleteExpired(before = any(), limit = any()) } returns
                    batch andThenThrows DataAccessResourceFailureException("database down")
                val (purged, logs) = captureLogs(loggerName = loggerName) { lane.scheduler.purgeOnce() }

                then("the tick returns the rows already deleted and one ERROR names the table and that count") {
                    purged shouldBe 1000
                    logs.messagesAt(level = Level.ERROR) shouldBe
                        listOf("OAuth state retention purge of google_oauth_state failed after 1000 rows")
                    verify(exactly = 2) { lane.stateRepository.deleteExpired(before = now, limit = batch) }
                }
            }

            `when`("the delete throws an error") {
                val lane = StateRetentionLane(deletes = listOf(0))
                every { lane.stateRepository.deleteExpired(before = any(), limit = any()) } throws
                    LinkageError("broken class")

                then("the error is not swallowed") {
                    shouldThrow<LinkageError> { lane.scheduler.purge() }
                }
            }

            `when`("no state has expired") {
                val lane = StateRetentionLane(deletes = listOf(0))
                val (purged, logs) = captureLogs(loggerName = loggerName) { lane.scheduler.purgeOnce() }

                then("one call is made and nothing is logged") {
                    purged shouldBe 0
                    logs.shouldBeEmpty()
                    verify(exactly = 1) { lane.stateRepository.deleteExpired(before = now, limit = batch) }
                }
            }
        }

        given("the scheduled trigger") {
            `when`("its annotations are read") {
                val schedulerClass = GoogleOAuthStateRetentionScheduler::class.java
                val scheduled = schedulerClass.getMethod("purge").getAnnotation(Scheduled::class.java)

                then("it is a scanned component, run hourly and first at random 10 to 20 minutes after start") {
                    schedulerClass.isAnnotationPresent(Component::class.java) shouldBe true
                    scheduled.fixedDelay shouldBe Duration.ofHours(1L).toMillis()
                    scheduled.initialDelayString shouldBe "\${random.long[600000,1200000]}"
                }
            }
        }
    })
