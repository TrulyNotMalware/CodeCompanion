package dev.notypie.application.service.relay

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.outbox.DEFAULT_TEST_NOW
import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class OutboxRecoverySchedulerTest :
    BehaviorSpec({
        val cutoff = DEFAULT_TEST_NOW.minusSeconds(300L)

        fun relayWithSlots(slots: Int = Int.MAX_VALUE): MessageRelayService =
            mockk {
                every { reserveDispatchSlots(wanted = any()) } answers { minOf(firstArg<Int>(), slots) }
                every { releaseDispatchSlots(count = any()) } just Runs
            }

        fun scheduler(repository: MessageOutboxRepository, relay: MessageRelayService) =
            OutboxRecoveryScheduler(
                outboxRepository = repository,
                messageRelayService = relay,
                appConfig = AppConfig(),
                clock = createFixedUtcClock(),
            )

        given("stuck IN_PROGRESS rows and stale PENDING rows older than the threshold") {
            val repository = mockk<MessageOutboxRepository>()
            val relay = relayWithSlots()
            every { repository.findStuckInProgress(olderThan = cutoff, limit = 100) } returns
                listOf(
                    createOutboxRow(
                        eventId = "stuck-won",
                        createdAt = DEFAULT_TEST_NOW.minusHours(1L),
                        attemptCount = 2,
                    ),
                    createOutboxRow(
                        eventId = "stuck-lost",
                        createdAt = DEFAULT_TEST_NOW.minusHours(1L),
                        attemptCount = 1,
                    ),
                )
            every {
                repository.reclaimStuck(
                    eventId = "stuck-won",
                    attemptCount = 2,
                    olderThan = cutoff,
                    now = DEFAULT_TEST_NOW,
                )
            } returns 1
            every {
                repository.reclaimStuck(
                    eventId = "stuck-lost",
                    attemptCount = 1,
                    olderThan = cutoff,
                    now = DEFAULT_TEST_NOW,
                )
            } returns 0
            every { repository.findStalePending(olderThan = cutoff, limit = 100) } returns
                listOf(createOutboxRow(eventId = "stale-won"), createOutboxRow(eventId = "stale-lost"))
            every { repository.claimPending(eventId = "stale-won", attemptCount = 0, now = any()) } returns 1
            every { repository.claimPending(eventId = "stale-lost", attemptCount = 0, now = any()) } returns 0
            val dispatched = slot<List<OutboxClaim>>()
            every { relay.batchPendingMessages(claims = capture(dispatched)) } returns Unit

            `when`("the sweep runs") {
                val count = scheduler(repository = repository, relay = relay).recoverOnce()

                then("only the rows whose CAS this instance won are re-dispatched, each with its next attempt") {
                    count shouldBe 2
                    dispatched.captured.map { it.row.eventId to it.attempt } shouldBe
                        listOf("stuck-won" to 3, "stale-won" to 1)
                }
            }
        }

        given("more stuck and stale rows than the relay has free slots") {
            val repository = mockk<MessageOutboxRepository>()
            val relay = relayWithSlots(slots = 2)
            every { repository.findStuckInProgress(olderThan = cutoff, limit = 100) } returns
                listOf(
                    createOutboxRow(eventId = "stuck-a", createdAt = DEFAULT_TEST_NOW.minusHours(1L), attemptCount = 1),
                    createOutboxRow(eventId = "stuck-b", createdAt = DEFAULT_TEST_NOW.minusHours(1L), attemptCount = 1),
                    createOutboxRow(eventId = "stuck-c", createdAt = DEFAULT_TEST_NOW.minusHours(1L), attemptCount = 1),
                )
            every {
                repository.reclaimStuck(eventId = any(), attemptCount = 1, olderThan = cutoff, now = DEFAULT_TEST_NOW)
            } returns 1
            every { repository.findStalePending(olderThan = cutoff, limit = 100) } returns
                listOf(createOutboxRow(eventId = "stale"))
            val dispatched = slot<List<OutboxClaim>>()
            every { relay.batchPendingMessages(claims = capture(dispatched)) } returns Unit

            `when`("the sweep runs") {
                val count = scheduler(repository = repository, relay = relay).recoverOnce()

                then("it claims only as many rows as it reserved, reclaims first, and leaves the rest unclaimed") {
                    count shouldBe 2
                    dispatched.captured.map { it.row.eventId } shouldBe listOf("stuck-a", "stuck-b")
                    verify(exactly = 0) {
                        repository.reclaimStuck(
                            eventId = "stuck-c",
                            attemptCount = any(),
                            olderThan = any(),
                            now = any(),
                        )
                    }
                    verify(exactly = 0) { repository.claimPending(eventId = any(), attemptCount = any(), now = any()) }
                }
            }
        }

        given("a row that has been IN_PROGRESS longer than the give-up window") {
            val repository = mockk<MessageOutboxRepository>()
            val relay = relayWithSlots()
            every { repository.findStuckInProgress(olderThan = cutoff, limit = 100) } returns
                listOf(createOutboxRow(eventId = "old", createdAt = DEFAULT_TEST_NOW.minusHours(25L), attemptCount = 4))
            every {
                repository.abandonStuck(eventId = "old", attemptCount = 4, olderThan = cutoff, now = DEFAULT_TEST_NOW)
            } returns 1
            every { repository.findStalePending(olderThan = any(), limit = any()) } returns emptyList()

            `when`("the sweep runs") {
                val count = scheduler(repository = repository, relay = relay).recoverOnce()

                then("it is marked FAILURE, guarded by the attempt and age the sweep observed") {
                    count shouldBe 0
                    verify(exactly = 1) {
                        repository.abandonStuck(
                            eventId = "old",
                            attemptCount = 4,
                            olderThan = cutoff,
                            now = DEFAULT_TEST_NOW,
                        )
                    }
                    verify(exactly = 0) {
                        repository.reclaimStuck(eventId = any(), attemptCount = any(), olderThan = any(), now = any())
                    }
                    verify(exactly = 0) { relay.batchPendingMessages(claims = any()) }
                }
            }
        }

        given("a poison row that spent its send budget well inside the give-up window") {
            val repository = mockk<MessageOutboxRepository>()
            val relay = relayWithSlots()
            every { repository.findStuckInProgress(olderThan = cutoff, limit = 100) } returns
                listOf(
                    createOutboxRow(
                        eventId = "poison",
                        createdAt = DEFAULT_TEST_NOW.minusHours(1L),
                        attemptCount = 12,
                        sendCount = 10,
                    ),
                    createOutboxRow(
                        eventId = "rate-limited",
                        createdAt = DEFAULT_TEST_NOW.minusHours(1L),
                        attemptCount = 30,
                        sendCount = 9,
                    ),
                )
            every {
                repository.abandonStuck(
                    eventId = "poison",
                    attemptCount = 12,
                    olderThan = cutoff,
                    now = DEFAULT_TEST_NOW,
                )
            } returns 1
            every {
                repository.reclaimStuck(
                    eventId = "rate-limited",
                    attemptCount = 30,
                    olderThan = cutoff,
                    now = DEFAULT_TEST_NOW,
                )
            } returns 1
            every { repository.findStalePending(olderThan = any(), limit = any()) } returns emptyList()
            val dispatched = slot<List<OutboxClaim>>()
            every { relay.batchPendingMessages(claims = capture(dispatched)) } returns Unit

            `when`("the sweep runs with the default budget of 10 sends") {
                scheduler(repository = repository, relay = relay).recoverOnce()

                then("the row at the send limit is abandoned; claims that never sent do not count") {
                    verify(exactly = 1) {
                        repository.abandonStuck(
                            eventId = "poison",
                            attemptCount = 12,
                            olderThan = cutoff,
                            now = DEFAULT_TEST_NOW,
                        )
                    }
                    dispatched.captured.map { it.row.eventId to it.attempt } shouldBe listOf("rate-limited" to 31)
                }
            }
        }

        given("a stuck row that a fresh owner renewed between the read and the abandon") {
            val repository = mockk<MessageOutboxRepository>()
            val relay = relayWithSlots()
            every { repository.findStuckInProgress(olderThan = cutoff, limit = 100) } returns
                listOf(
                    createOutboxRow(eventId = "raced", createdAt = DEFAULT_TEST_NOW.minusHours(25L), attemptCount = 2),
                )
            every {
                repository.abandonStuck(eventId = "raced", attemptCount = 2, olderThan = cutoff, now = DEFAULT_TEST_NOW)
            } returns 0
            every { repository.findStalePending(olderThan = any(), limit = any()) } returns emptyList()

            `when`("the sweep runs") {
                val count = scheduler(repository = repository, relay = relay).recoverOnce()

                then("the lost abandon CAS is neither retried nor re-dispatched") {
                    count shouldBe 0
                    verify(exactly = 0) { relay.batchPendingMessages(claims = any()) }
                }
            }
        }

        given("outbox budgets and thresholds") {
            then("zero or negative values are rejected when the configuration binds") {
                shouldThrow<IllegalArgumentException> { AppConfig.Outbox.Polling(maxSends = 0) }
                shouldThrow<IllegalArgumentException> { AppConfig.Outbox.Polling(batchSize = 0) }
                shouldThrow<IllegalArgumentException> { AppConfig.Outbox.Polling(stuckInProgressSeconds = -1L) }
                shouldThrow<IllegalArgumentException> { AppConfig.Outbox.Polling(giveUpAfterHours = 0L) }
                shouldThrow<IllegalArgumentException> { AppConfig.Outbox.Health(retryingSendThreshold = 0) }
                shouldThrow<IllegalArgumentException> { AppConfig.Outbox.Health(stuckThresholdSeconds = 0L) }
            }
        }

        given("nothing to recover") {
            val repository = mockk<MessageOutboxRepository>()
            val relay = relayWithSlots()
            every { repository.findStuckInProgress(olderThan = any(), limit = any()) } returns emptyList()
            every { repository.findStalePending(olderThan = any(), limit = any()) } returns emptyList()

            `when`("the sweep runs") {
                scheduler(repository = repository, relay = relay).recoverOnce()

                then("the relay is not touched") {
                    verify(exactly = 0) { relay.batchPendingMessages(claims = any()) }
                }
            }
        }
    })
