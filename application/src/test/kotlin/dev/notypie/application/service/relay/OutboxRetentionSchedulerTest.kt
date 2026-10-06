package dev.notypie.application.service.relay

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.outbox.DEFAULT_TEST_NOW
import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class OutboxRetentionSchedulerTest :
    BehaviorSpec({
        given("a retention of 7 days and a batch of 50") {
            val repository = mockk<MessageOutboxRepository>()
            every { repository.deleteTerminalOlderThan(olderThan = any(), limit = any()) } returnsMany listOf(50, 50, 3)
            val scheduler =
                OutboxRetentionScheduler(
                    outboxRepository = repository,
                    appConfig =
                        AppConfig(
                            outbox =
                                AppConfig.Outbox(
                                    retention = AppConfig.Outbox.Retention(days = 7L, batchSize = 50),
                                ),
                        ),
                    clock = createFixedUtcClock(),
                )

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
                every { repository.deleteTerminalOlderThan(olderThan = any(), limit = any()) } throws
                    IllegalStateException("db")

                then("the tick swallows it so the scheduler thread survives") {
                    scheduler.purge()
                }
            }
        }
    })
