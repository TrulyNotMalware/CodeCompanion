package dev.notypie.application.service.relay

import dev.notypie.application.outbox.DEFAULT_TEST_NOW
import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.application.outbox.createPollingProcessorFixture
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PollingMessageProcessorTest :
    BehaviorSpec({

        given("PollingMessageProcessor.pollPending") {
            `when`("no PENDING rows exist") {
                val (outboxRepository, relayService, processor) = createPollingProcessorFixture()
                every { outboxRepository.findPendingMessages(limit = 100) } returns emptyList()

                processor.pollPending()

                then("nothing is dispatched and no claim is attempted") {
                    verify(exactly = 0) { relayService.batchPendingMessages(claims = any()) }
                    verify(exactly = 0) {
                        outboxRepository.claimPending(eventId = any(), attemptCount = any(), now = any())
                    }
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
                every { outboxRepository.claimPending(eventId = any(), attemptCount = any(), now = any()) } returns 1

                val captured = slot<List<OutboxClaim>>()
                every { relayService.batchPendingMessages(claims = capture(captured)) } returns Unit

                processor.pollPending()

                then("every row is claimed with the clock's time and forwarded once as attempt 1") {
                    captured.captured shouldHaveSize 3
                    captured.captured.map { it.attempt } shouldBe listOf(1, 1, 1)
                    verify(exactly = 1) { relayService.batchPendingMessages(claims = any()) }
                    listOf("e1", "e2", "e3").forEach { id ->
                        verify(exactly = 1) {
                            outboxRepository.claimPending(eventId = id, attemptCount = 0, now = DEFAULT_TEST_NOW)
                        }
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
                every { outboxRepository.claimPending(eventId = "a", attemptCount = 0, now = any()) } returns 1
                every { outboxRepository.claimPending(eventId = "b", attemptCount = 0, now = any()) } returns 0
                every { outboxRepository.claimPending(eventId = "c", attemptCount = 0, now = any()) } returns 1

                val captured = slot<List<OutboxClaim>>()
                every { relayService.batchPendingMessages(claims = capture(captured)) } returns Unit

                processor.pollPending()

                then("exactly the rows this poller won are dispatched, not a prefix of the candidate list") {
                    captured.captured.map { it.row.eventId } shouldBe listOf("a", "c")
                }
            }

            `when`("the relay executor has room for only two claims") {
                val relay = mockk<SlackMessageRelayServiceImpl>(relaxed = true)
                every { relay.freeDispatchSlots() } returns 2
                val (outboxRepository, relayService, processor) = createPollingProcessorFixture(relayService = relay)
                every { outboxRepository.findPendingMessages(limit = 2) } returns
                    listOf(createOutboxRow(eventId = "p"), createOutboxRow(eventId = "q"))
                every { outboxRepository.claimPending(eventId = any(), attemptCount = 0, now = any()) } returns 1
                val captured = slot<List<OutboxClaim>>()
                every { relayService.batchPendingMessages(claims = capture(captured)) } returns Unit

                processor.pollPending()

                then("the PENDING read is capped at the free slots, so nothing is claimed that cannot be queued") {
                    verify(exactly = 1) { outboxRepository.findPendingMessages(limit = 2) }
                    captured.captured.map { it.row.eventId } shouldBe listOf("p", "q")
                }
            }

            `when`("the relay executor is full") {
                val relay = mockk<SlackMessageRelayServiceImpl>(relaxed = true)
                every { relay.freeDispatchSlots() } returns 0
                val (outboxRepository, relayService, processor) = createPollingProcessorFixture(relayService = relay)

                processor.pollPending()

                then("the tick neither reads nor claims, leaving every row PENDING for a later tick") {
                    verify(exactly = 0) { outboxRepository.findPendingMessages(limit = any()) }
                    verify(exactly = 0) { relayService.batchPendingMessages(claims = any()) }
                }
            }

            `when`("a tick fires while the previous tick is still running") {
                val (outboxRepository, _, processor) = createPollingProcessorFixture()
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val reads = AtomicInteger(0)
                every { outboxRepository.findPendingMessages(limit = 100) } answers {
                    if (reads.incrementAndGet() == 1) {
                        entered.countDown()
                        release.await(5L, TimeUnit.SECONDS)
                    }
                    emptyList()
                }

                val first = thread { processor.pollPending() }
                entered.await(5L, TimeUnit.SECONDS)
                processor.pollPending()
                val readsWhileOverlapping = reads.get()
                release.countDown()
                first.join(5_000L)
                processor.pollPending()

                then("the overlapping tick returns without reading, and the next tick after it runs again") {
                    readsWhileOverlapping shouldBe 1
                    reads.get() shouldBe 2
                }
            }

            `when`("every candidate was already taken") {
                val (outboxRepository, relayService, processor) = createPollingProcessorFixture()
                every { outboxRepository.findPendingMessages(limit = 100) } returns
                    listOf(createOutboxRow(eventId = "x"))
                every { outboxRepository.claimPending(eventId = "x", attemptCount = 0, now = any()) } returns 0

                processor.pollPending()

                then("nothing is dispatched even though the candidate read returned a row") {
                    verify(exactly = 0) { relayService.batchPendingMessages(claims = any()) }
                }
            }
        }
    })
