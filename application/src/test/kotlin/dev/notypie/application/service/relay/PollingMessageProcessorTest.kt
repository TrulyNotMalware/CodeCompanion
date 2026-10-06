package dev.notypie.application.service.relay

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.outbox.DEFAULT_TEST_NOW
import dev.notypie.application.outbox.QueuedExecutor
import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.application.outbox.createPollingProcessorFixture
import dev.notypie.application.outbox.createRelayService
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

            `when`("the relay has room for only two claims") {
                val relay = mockk<SlackMessageRelayServiceImpl>(relaxed = true)
                every { relay.reserveDispatchSlots(wanted = any()) } answers { minOf(firstArg<Int>(), 2) }
                val (outboxRepository, relayService, processor) = createPollingProcessorFixture(relayService = relay)
                every { outboxRepository.findPendingMessages(limit = 2) } returns
                    listOf(createOutboxRow(eventId = "p"), createOutboxRow(eventId = "q"))
                every { outboxRepository.claimPending(eventId = any(), attemptCount = 0, now = any()) } returns 1
                val captured = slot<List<OutboxClaim>>()
                every { relayService.batchPendingMessages(claims = capture(captured)) } returns Unit

                processor.pollPending()

                then("only that many rows are read and claimed, the rest stay PENDING for a later tick") {
                    captured.captured.map { it.row.eventId } shouldBe listOf("p", "q")
                    verify(exactly = 0) { outboxRepository.findPendingMessages(limit = 100) }
                }
            }

            `when`("the relay has no free slot") {
                val relay = mockk<SlackMessageRelayServiceImpl>(relaxed = true)
                every { relay.reserveDispatchSlots(wanted = any()) } returns 0
                val (outboxRepository, relayService, processor) = createPollingProcessorFixture(relayService = relay)

                processor.pollPending()

                then("no row is read or claimed") {
                    verify(exactly = 0) { outboxRepository.findPendingMessages(limit = any()) }
                    verify(exactly = 0) { relayService.batchPendingMessages(claims = any()) }
                }
            }

            `when`("the recovery sweep runs while a poller tick holds every relay slot") {
                val outboxRepository = mockk<MessageOutboxRepository>()
                val executor = QueuedExecutor()
                val appConfig = AppConfig(outbox = AppConfig.Outbox(polling = AppConfig.Outbox.Polling(batchSize = 3)))
                val relay =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        relayTaskExecutor = executor,
                        appConfig = appConfig,
                    )
                val (_, _, processor) =
                    createPollingProcessorFixture(
                        batchSize = 3,
                        outboxRepository = outboxRepository,
                        relayService = relay,
                    )
                val sweep =
                    OutboxRecoveryScheduler(
                        outboxRepository = outboxRepository,
                        messageRelayService = relay,
                        appConfig = appConfig,
                        clock = createFixedUtcClock(),
                    )
                val pollerReading = CountDownLatch(1)
                val sweepDone = CountDownLatch(1)
                every { outboxRepository.findPendingMessages(limit = 3) } answers {
                    pollerReading.countDown()
                    sweepDone.await(5L, TimeUnit.SECONDS)
                    List(size = 3) { createOutboxRow(eventId = "fresh-$it") }
                }
                every { outboxRepository.findStuckInProgress(olderThan = any(), limit = any()) } returns emptyList()
                every { outboxRepository.findStalePending(olderThan = any(), limit = any()) } returns
                    List(size = 3) { index ->
                        createOutboxRow(eventId = "stale-$index", createdAt = DEFAULT_TEST_NOW.minusHours(1L))
                    }
                every { outboxRepository.claimPending(eventId = any(), attemptCount = 0, now = any()) } returns 1

                val tick = thread { processor.pollPending() }
                pollerReading.await(5L, TimeUnit.SECONDS)
                val sweptClaims = sweep.recoverOnce()
                sweepDone.countDown()
                tick.join(5_000L)

                then("the sweep claims nothing, and together they never claim more rows than the relay can queue") {
                    sweptClaims shouldBe 0
                    verify(exactly = 0) {
                        outboxRepository.claimPending(
                            eventId = match { it.startsWith("stale-") },
                            attemptCount = any(),
                            now = any(),
                        )
                    }
                    executor.size shouldBe 3
                    relay.reserveDispatchSlots(wanted = Int.MAX_VALUE) shouldBe 0
                }
            }

            `when`("reading the PENDING rows throws after the tick reserved its slots") {
                val outboxRepository = mockk<MessageOutboxRepository>()
                val relay = createRelayService(outboxRepository = outboxRepository)
                val (_, _, processor) =
                    createPollingProcessorFixture(outboxRepository = outboxRepository, relayService = relay)
                every { outboxRepository.findPendingMessages(limit = any()) } throws IllegalStateException("db down")

                shouldThrow<IllegalStateException> { processor.pollPending() }

                then("every reserved slot is given back for the next tick") {
                    relay.reserveDispatchSlots(wanted = Int.MAX_VALUE) shouldBe AppConfig().outbox.polling.batchSize
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
