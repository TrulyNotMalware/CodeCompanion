package dev.notypie.application.service.relay

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.outbox.DEFAULT_TEST_NOW
import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class OutboxRecoverySchedulerTest :
    BehaviorSpec({
        val cutoff = DEFAULT_TEST_NOW.minusSeconds(300L)

        fun scheduler(repository: MessageOutboxRepository, relay: MessageRelayService) =
            OutboxRecoveryScheduler(
                outboxRepository = repository,
                messageRelayService = relay,
                appConfig = AppConfig(),
                clock = createFixedUtcClock(),
            )

        given("stuck IN_PROGRESS rows and stale PENDING rows older than the threshold") {
            val repository = mockk<MessageOutboxRepository>()
            val relay = mockk<MessageRelayService>()
            every { repository.findStuckInProgress(olderThan = cutoff, limit = 100) } returns
                listOf(
                    createOutboxRow(eventId = "stuck-won", createdAt = DEFAULT_TEST_NOW.minusHours(1L)),
                    createOutboxRow(eventId = "stuck-lost", createdAt = DEFAULT_TEST_NOW.minusHours(1L)),
                )
            every { repository.reclaimStuck(eventId = "stuck-won", olderThan = cutoff) } returns 1
            every { repository.reclaimStuck(eventId = "stuck-lost", olderThan = cutoff) } returns 0
            every { repository.findStalePending(olderThan = cutoff, limit = 100) } returns
                listOf(createOutboxRow(eventId = "stale-won"), createOutboxRow(eventId = "stale-lost"))
            every { repository.claimPending(eventIds = listOf("stale-won")) } returns 1
            every { repository.claimPending(eventIds = listOf("stale-lost")) } returns 0
            val dispatched = slot<List<OutboxMessage>>()
            every { relay.batchPendingMessages(pendingMessages = capture(dispatched)) } returns Unit

            `when`("the sweep runs") {
                val count = scheduler(repository = repository, relay = relay).recoverOnce()

                then("only the rows whose CAS this instance won are re-dispatched") {
                    count shouldBe 2
                    dispatched.captured.map { it.eventId } shouldBe listOf("stuck-won", "stale-won")
                }
            }
        }

        given("a row that has been IN_PROGRESS longer than the give-up window") {
            val repository = mockk<MessageOutboxRepository>()
            val relay = mockk<MessageRelayService>()
            every { repository.findStuckInProgress(olderThan = cutoff, limit = 100) } returns
                listOf(createOutboxRow(eventId = "poison", createdAt = DEFAULT_TEST_NOW.minusHours(25L)))
            every { repository.abandonStuck(eventId = "poison") } returns 1
            every { repository.findStalePending(olderThan = any(), limit = any()) } returns emptyList()

            `when`("the sweep runs") {
                val count = scheduler(repository = repository, relay = relay).recoverOnce()

                then("it is marked FAILURE instead of being re-dispatched again") {
                    count shouldBe 0
                    verify(exactly = 1) { repository.abandonStuck(eventId = "poison") }
                    verify(exactly = 0) { repository.reclaimStuck(eventId = "poison", olderThan = any()) }
                    verify(exactly = 0) { relay.batchPendingMessages(pendingMessages = any()) }
                }
            }
        }

        given("nothing to recover") {
            val repository = mockk<MessageOutboxRepository>()
            val relay = mockk<MessageRelayService>()
            every { repository.findStuckInProgress(olderThan = any(), limit = any()) } returns emptyList()
            every { repository.findStalePending(olderThan = any(), limit = any()) } returns emptyList()

            `when`("the sweep runs") {
                scheduler(repository = repository, relay = relay).recoverOnce()

                then("the relay is not touched") {
                    verify(exactly = 0) { relay.batchPendingMessages(pendingMessages = any()) }
                }
            }
        }
    })
