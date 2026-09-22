package dev.notypie.application.service.relay

import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.application.outbox.createPollingProcessorFixture
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.slot
import io.mockk.verify

class PollingMessageProcessorTest :
    BehaviorSpec({

        given("PollingMessageProcessor.pollPending") {
            `when`("no PENDING and no stuck IN_PROGRESS rows exist") {
                val (outboxRepository, relayService, processor) = createPollingProcessorFixture()
                every { outboxRepository.findPendingMessages(limit = 100) } returns emptyList()

                processor.pollPending()

                then("nothing is dispatched and no claim is attempted") {
                    verify(exactly = 0) { relayService.batchPendingMessages(pendingMessages = any()) }
                    verify(exactly = 0) { outboxRepository.claimPending(eventIds = any()) }
                }
            }

            `when`("PENDING candidates are returned and the claim succeeds for all of them") {
                val (outboxRepository, relayService, processor) = createPollingProcessorFixture()
                val candidates =
                    listOf(
                        createOutboxRow(eventId = "e1"),
                        createOutboxRow(eventId = "e2"),
                        createOutboxRow(eventId = "e3"),
                    )
                every { outboxRepository.findPendingMessages(limit = 100) } returns candidates
                every { outboxRepository.claimPending(eventIds = any()) } returns 1

                val captured = slot<List<OutboxMessage>>()
                every { relayService.batchPendingMessages(pendingMessages = capture(captured)) } returns Unit

                processor.pollPending()

                then("all candidates are forwarded to the relay service exactly once") {
                    captured.captured shouldHaveSize 3
                    captured.captured.map { it.eventId } shouldHaveSize 3
                    verify(exactly = 1) { relayService.batchPendingMessages(pendingMessages = any()) }
                    listOf("e1", "e2", "e3").forEach { id ->
                        verify(exactly = 1) { outboxRepository.claimPending(eventIds = listOf(id)) }
                    }
                }
            }

            `when`("another poller wins the middle candidate (race with another poller)") {
                val (outboxRepository, relayService, processor) = createPollingProcessorFixture()
                val candidates =
                    listOf(
                        createOutboxRow(eventId = "a"),
                        createOutboxRow(eventId = "b"),
                        createOutboxRow(eventId = "c"),
                    )
                every { outboxRepository.findPendingMessages(limit = 100) } returns candidates
                every { outboxRepository.claimPending(eventIds = listOf("a")) } returns 1
                every { outboxRepository.claimPending(eventIds = listOf("b")) } returns 0
                every { outboxRepository.claimPending(eventIds = listOf("c")) } returns 1

                val captured = slot<List<OutboxMessage>>()
                every { relayService.batchPendingMessages(pendingMessages = capture(captured)) } returns Unit

                processor.pollPending()

                then("exactly the rows this poller won are dispatched, not a prefix of the candidate list") {
                    captured.captured.map { it.eventId } shouldBe listOf("a", "c")
                }
            }

            `when`("the claim count is zero (every candidate was already taken)") {
                val (outboxRepository, relayService, processor) = createPollingProcessorFixture()
                every { outboxRepository.findPendingMessages(limit = 100) } returns
                    listOf(createOutboxRow(eventId = "x"))
                every { outboxRepository.claimPending(eventIds = listOf("x")) } returns 0

                processor.pollPending()

                then("nothing is dispatched even though the candidate read returned a row") {
                    verify(exactly = 0) { relayService.batchPendingMessages(pendingMessages = any()) }
                }
            }
        }
    })
