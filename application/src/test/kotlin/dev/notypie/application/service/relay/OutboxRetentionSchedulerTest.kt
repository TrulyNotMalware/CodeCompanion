package dev.notypie.application.service.relay

import ch.qos.logback.classic.Level
import dev.notypie.application.configurations.createOutboxRetentionAppConfig
import dev.notypie.application.outbox.DEFAULT_TEST_NOW
import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.logging.captureLogs
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class OutboxRetentionSchedulerTest :
    BehaviorSpec({
        fun schedulerOver(repository: MessageOutboxRepository): OutboxRetentionScheduler =
            OutboxRetentionScheduler(
                outboxRepository = repository,
                appConfig = createOutboxRetentionAppConfig(),
                clock = createFixedUtcClock(),
            )

        given("a retention of 7 days and a batch of 50") {
            val repository = mockk<MessageOutboxRepository>()
            every { repository.deleteTerminalOlderThan(olderThan = any(), limit = any()) } returnsMany listOf(50, 50, 3)
            val scheduler = schedulerOver(repository = repository)

            `when`("the purge runs") {
                val deleted = scheduler.purgeOnce()

                then("batches run until one comes back short, all against the same cutoff") {
                    deleted shouldBe 103
                    verify(exactly = 3) {
                        repository.deleteTerminalOlderThan(olderThan = DEFAULT_TEST_NOW.minusDays(7L), limit = 50)
                    }
                }
            }

            `when`("the repository throws inside the scheduled tick") {
                val thrown = IllegalStateException("db")
                every { repository.deleteTerminalOlderThan(olderThan = any(), limit = any()) } throws thrown

                then("the tick swallows it so the scheduler thread survives") {
                    shouldNotThrowAny { scheduler.purge() }
                }

                then("the tick logs one ERROR line carrying the exception") {
                    val errors =
                        captureLogs(loggerName = OutboxRetentionScheduler::class.java.packageName) {
                            scheduler.purge()
                        }.eventsAt(level = Level.ERROR)
                    errors.map { it.formattedMessage } shouldBe listOf("Outbox retention purge failed")
                    val throwable = errors.single().throwableProxy.shouldNotBeNull()
                    throwable.className shouldBe IllegalStateException::class.java.name
                    throwable.message shouldBe "db"
                }

                then("purgeOnce lets the same exception out, so only the tick contains it") {
                    shouldThrow<IllegalStateException> { scheduler.purgeOnce() } shouldBeSameInstanceAs thrown
                }
            }

            `when`("the repository throws an Error inside the scheduled tick") {
                val thrown = StackOverflowError("deep")
                every { repository.deleteTerminalOlderThan(olderThan = any(), limit = any()) } throws thrown

                then("the same Error propagates out of the tick") {
                    shouldThrow<StackOverflowError> { scheduler.purge() } shouldBeSameInstanceAs thrown
                }
            }
        }

        given("a batch of 50 and a backlog that fills every batch") {
            val repository = mockk<MessageOutboxRepository>()
            every { repository.deleteTerminalOlderThan(olderThan = any(), limit = any()) } returns 50
            val scheduler = schedulerOver(repository = repository)

            `when`("the purge runs") {
                val deleted = scheduler.purgeOnce()

                then("it stops after 20 batches and leaves the rest for the next tick") {
                    deleted shouldBe 1_000
                    verify(exactly = 20) {
                        repository.deleteTerminalOlderThan(olderThan = DEFAULT_TEST_NOW.minusDays(7L), limit = 50)
                    }
                }
            }
        }
    })
