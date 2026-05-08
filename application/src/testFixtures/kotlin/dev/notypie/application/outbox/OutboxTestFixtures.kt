package dev.notypie.application.outbox

import dev.notypie.application.service.relay.PollingMessageProcessor
import dev.notypie.application.service.relay.SlackMessageRelayServiceImpl
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

val DEFAULT_TEST_NOW: LocalDateTime = LocalDateTime.of(2026, 4, 28, 12, 0, 0)

fun createFixedUtcClock(now: LocalDateTime = DEFAULT_TEST_NOW): Clock =
    Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneId.of("UTC"))

fun createOutboxRow(eventId: String): OutboxMessage =
    mockk(relaxed = true) {
        every { this@mockk.eventId } returns eventId
    }

data class PollingProcessorFixture(
    val outboxRepository: MessageOutboxRepository,
    val relayService: SlackMessageRelayServiceImpl,
    val processor: PollingMessageProcessor,
)

fun createPollingProcessorFixture(
    clock: Clock = createFixedUtcClock(),
    batchSize: Int = 100,
    stuckInProgressSeconds: Long = 300L,
    outboxRepository: MessageOutboxRepository = mockk(),
    relayService: SlackMessageRelayServiceImpl = mockk(relaxed = true),
): PollingProcessorFixture =
    PollingProcessorFixture(
        outboxRepository = outboxRepository,
        relayService = relayService,
        processor =
            PollingMessageProcessor(
                outboxRepository = outboxRepository,
                messageRelayService = relayService,
                clock = clock,
                batchSize = batchSize,
                stuckInProgressSeconds = stuckInProgressSeconds,
            ),
    )

fun MessageOutboxRepository.stubOutboxStatus(
    pendingCount: Long = 0L,
    stuckPendingCount: Long = 0L,
    oldestPendingCreatedAt: LocalDateTime? = null,
    inProgressCount: Long = 0L,
    stuckInProgressCount: Long = 0L,
    oldestInProgressUpdatedAt: LocalDateTime? = null,
) {
    every { countPending() } returns pendingCount
    every { countPendingOlderThan(threshold = any()) } returns stuckPendingCount
    every { findOldestPendingCreatedAt() } returns oldestPendingCreatedAt
    every { countInProgress() } returns inProgressCount
    every { countInProgressOlderThan(threshold = any()) } returns stuckInProgressCount
    every { findOldestInProgressUpdatedAt() } returns oldestInProgressUpdatedAt
}
