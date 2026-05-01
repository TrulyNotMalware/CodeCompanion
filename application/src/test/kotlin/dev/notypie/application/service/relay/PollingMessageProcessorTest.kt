package dev.notypie.application.service.relay

import dev.notypie.application.service.relay.dto.NoParameter
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class PollingMessageProcessorTest :
    BehaviorSpec({
        val now = LocalDateTime.of(2026, 4, 28, 12, 0, 0)
        val zone = ZoneId.of("UTC")
        val clock = Clock.fixed(now.toInstant(ZoneOffset.UTC), zone)

        fun newRow(eventId: String): OutboxMessage =
            mockk(relaxed = true) {
                every { this@mockk.eventId } returns eventId
            }

        given("PollingMessageProcessor.getPendingMessages") {
            `when`("no PENDING and no stuck IN_PROGRESS rows exist") {
                val outboxRepository = mockk<MessageOutboxRepository>()
                val relayService = mockk<SlackMessageRelayServiceImpl>(relaxed = true)
                val processor =
                    PollingMessageProcessor(
                        outboxRepository = outboxRepository,
                        messageRelayService = relayService,
                        clock = clock,
                        batchSize = 100,
                        stuckInProgressSeconds = 300L,
                    )

                every { outboxRepository.findStuckInProgress(olderThan = any(), limit = 100) } returns emptyList()
                every { outboxRepository.findPendingMessages(limit = 100, offset = 0) } returns emptyList()

                processor.getPendingMessages(messageParameter = NoParameter)

                then("nothing is dispatched and no claim is attempted") {
                    verify(exactly = 0) { relayService.batchPendingMessages(pendingMessages = any()) }
                    verify(exactly = 0) { outboxRepository.claimPending(eventIds = any()) }
                }
            }

            `when`("PENDING candidates are returned and the claim succeeds for all of them") {
                val outboxRepository = mockk<MessageOutboxRepository>()
                val relayService = mockk<SlackMessageRelayServiceImpl>(relaxed = true)
                val processor =
                    PollingMessageProcessor(
                        outboxRepository = outboxRepository,
                        messageRelayService = relayService,
                        clock = clock,
                        batchSize = 100,
                        stuckInProgressSeconds = 300L,
                    )
                val candidates =
                    listOf(
                        newRow(eventId = "e1"),
                        newRow(eventId = "e2"),
                        newRow(eventId = "e3"),
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
                val outboxRepository = mockk<MessageOutboxRepository>()
                val relayService = mockk<SlackMessageRelayServiceImpl>(relaxed = true)
                val processor =
                    PollingMessageProcessor(
                        outboxRepository = outboxRepository,
                        messageRelayService = relayService,
                        clock = clock,
                        batchSize = 100,
                        stuckInProgressSeconds = 300L,
                    )
                val candidates =
                    listOf(
                        newRow(eventId = "a"),
                        newRow(eventId = "b"),
                        newRow(eventId = "c"),
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
                val outboxRepository = mockk<MessageOutboxRepository>()
                val relayService = mockk<SlackMessageRelayServiceImpl>(relaxed = true)
                val processor =
                    PollingMessageProcessor(
                        outboxRepository = outboxRepository,
                        messageRelayService = relayService,
                        clock = clock,
                        batchSize = 100,
                        stuckInProgressSeconds = 300L,
                    )

                every { outboxRepository.findStuckInProgress(olderThan = any(), limit = any()) } returns emptyList()
                every { outboxRepository.findPendingMessages(limit = 100, offset = 0) } returns
                    listOf(newRow(eventId = "x"))
                every { outboxRepository.claimPending(eventIds = listOf("x")) } returns 0

                processor.getPendingMessages(messageParameter = NoParameter)

                then("nothing is dispatched even though the candidate read returned a row") {
                    verify(exactly = 0) { relayService.batchPendingMessages(pendingMessages = any()) }
                }
            }

            `when`("crash-orphaned IN_PROGRESS rows are detected") {
                val outboxRepository = mockk<MessageOutboxRepository>()
                val relayService = mockk<SlackMessageRelayServiceImpl>(relaxed = true)
                val processor =
                    PollingMessageProcessor(
                        outboxRepository = outboxRepository,
                        messageRelayService = relayService,
                        clock = clock,
                        batchSize = 100,
                        stuckInProgressSeconds = 300L,
                    )
                val orphaned = listOf(newRow(eventId = "orphan-1"), newRow(eventId = "orphan-2"))

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
