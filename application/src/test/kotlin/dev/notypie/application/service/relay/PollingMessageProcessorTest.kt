package dev.notypie.application.service.relay

import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.application.outbox.createPollingProcessorFixture
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.slot
import io.mockk.verify

class PollingMessageProcessorTest :
    BehaviorSpec({
        val clock = createFixedUtcClock()

        given("PollingMessageProcessor.getPendingMessages") {
            `when`("no PENDING and no stuck IN_PROGRESS rows exist") {
                val (outboxRepository, relayService, processor) = createPollingProcessorFixture(clock = clock)

                every { outboxRepository.findStuckInProgress(olderThan = any(), limit = 100) } returns emptyList()
                every { outboxRepository.findPendingMessages(limit = 100, offset = 0) } returns emptyList()

                processor.getPendingMessages(messageParameter = NoParameter)

                then("nothing is dispatched and no claim is attempted") {
                    verify(exactly = 0) { relayService.batchPendingMessages(pendingMessages = any()) }
                    verify(exactly = 0) { outboxRepository.claimPending(eventIds = any()) }
                }
            }

            `when`("PENDING candidates are returned and the claim succeeds for all of them") {
                val (outboxRepository, relayService, processor) = createPollingProcessorFixture(clock = clock)
                val candidates =
                    listOf(
                        createOutboxRow(eventId = "e1"),
                        createOutboxRow(eventId = "e2"),
                        createOutboxRow(eventId = "e3"),
                    )

                every { outboxRepository.findStuckInProgress(olderThan = any(), limit = any()) } returns emptyList()
                every { outboxRepository.findPendingMessages(limit = 100, offset = 0) } returns candidates
                every { outboxRepository.claimPending(eventIds = listOf("e1", "e2", "e3")) } returns 3

                val captured = slot<List<OutboxMessage>>()
                every { relayService.batchPendingMessages(pendingMessages = capture(captured)) } returns Unit

                processor.getPendingMessages(messageParameter = NoParameter)

                then("all candidates are forwarded to the relay service exactly once") {
                    captured.captured shouldHaveSize 3
                    captured.captured.map { it.eventId } shouldHaveSize 3
                    verify(exactly = 1) { relayService.batchPendingMessages(pendingMessages = any()) }
                    verify(exactly = 1) { outboxRepository.claimPending(eventIds = listOf("e1", "e2", "e3")) }
                }
            }

            `when`("the claim count is smaller than the candidate set (race with another poller)") {
                val (outboxRepository, relayService, processor) = createPollingProcessorFixture(clock = clock)
                val candidates =
                    listOf(
                        createOutboxRow(eventId = "a"),
                        createOutboxRow(eventId = "b"),
                        createOutboxRow(eventId = "c"),
                    )

                every { outboxRepository.findStuckInProgress(olderThan = any(), limit = any()) } returns emptyList()
                every { outboxRepository.findPendingMessages(limit = 100, offset = 0) } returns candidates
                // Suppose only 2 of the 3 PENDING rows we read were still PENDING by the time the
                // UPDATE ran — the third was claimed by a racing poller. We must dispatch 2, not 3.
                every { outboxRepository.claimPending(eventIds = listOf("a", "b", "c")) } returns 2

                val captured = slot<List<OutboxMessage>>()
                every { relayService.batchPendingMessages(pendingMessages = capture(captured)) } returns Unit

                processor.getPendingMessages(messageParameter = NoParameter)

                then("only the first claim-count rows are dispatched") {
                    captured.captured shouldHaveSize 2
                }
            }

            `when`("the claim count is zero (every candidate was already taken)") {
                val (outboxRepository, relayService, processor) = createPollingProcessorFixture(clock = clock)

                every { outboxRepository.findStuckInProgress(olderThan = any(), limit = any()) } returns emptyList()
                every { outboxRepository.findPendingMessages(limit = 100, offset = 0) } returns
                    listOf(createOutboxRow(eventId = "x"))
                every { outboxRepository.claimPending(eventIds = listOf("x")) } returns 0

                processor.getPendingMessages(messageParameter = NoParameter)

                then("nothing is dispatched even though the candidate read returned a row") {
                    verify(exactly = 0) { relayService.batchPendingMessages(pendingMessages = any()) }
                }
            }

            `when`("crash-orphaned IN_PROGRESS rows are detected") {
                val (outboxRepository, relayService, processor) = createPollingProcessorFixture(clock = clock)
                val orphaned =
                    listOf(
                        createOutboxRow(eventId = "orphan-1"),
                        createOutboxRow(eventId = "orphan-2"),
                    )

                every { outboxRepository.findStuckInProgress(olderThan = any(), limit = 100) } returns orphaned
                every { outboxRepository.findPendingMessages(limit = 100, offset = 0) } returns emptyList()

                val captured = slot<List<OutboxMessage>>()
                every { relayService.batchPendingMessages(pendingMessages = capture(captured)) } returns Unit

                processor.getPendingMessages(messageParameter = NoParameter)

                then("they are re-dispatched, no claim attempt is made for them") {
                    captured.captured shouldHaveSize 2
                    verify(exactly = 1) { relayService.batchPendingMessages(pendingMessages = any()) }
                    verify(exactly = 0) { outboxRepository.claimPending(eventIds = any()) }
                    confirmVerified(relayService)
                }
            }
        }
    })
