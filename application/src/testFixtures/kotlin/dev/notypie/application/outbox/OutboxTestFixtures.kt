package dev.notypie.application.outbox

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.service.relay.OutboxPayloadRenderer
import dev.notypie.application.service.relay.PollingMessageProcessor
import dev.notypie.application.service.relay.SlackMessageRelayServiceImpl
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.mockk.every
import io.mockk.mockk
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executor

val DEFAULT_TEST_NOW: LocalDateTime = LocalDateTime.of(2026, 4, 28, 12, 0, 0)

fun createFixedUtcClock(now: LocalDateTime = DEFAULT_TEST_NOW): Clock =
    Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneId.of("UTC"))

fun createOutboxRow(
    eventId: String,
    status: MessageStatus = MessageStatus.PENDING,
    createdAt: LocalDateTime = DEFAULT_TEST_NOW,
    attemptCount: Int = 0,
    sendCount: Int = 0,
): OutboxMessage =
    mockk(relaxed = true) {
        every { this@mockk.eventId } returns eventId
        every { this@mockk.status } returns status.name
        every { this@mockk.createdAt } returns createdAt
        every { this@mockk.attemptCount } returns attemptCount
        every { this@mockk.sendCount } returns sendCount
    }

data class PollingProcessorFixture(
    val outboxRepository: MessageOutboxRepository,
    val relayService: SlackMessageRelayServiceImpl,
    val processor: PollingMessageProcessor,
)

fun createPollingProcessorFixture(
    batchSize: Int = 100,
    outboxRepository: MessageOutboxRepository = mockk(),
    relayService: SlackMessageRelayServiceImpl = mockk(relaxed = true),
    clock: Clock = createFixedUtcClock(),
): PollingProcessorFixture =
    PollingProcessorFixture(
        outboxRepository = outboxRepository,
        relayService = relayService,
        processor =
            PollingMessageProcessor(
                outboxRepository = outboxRepository,
                messageRelayService = relayService,
                appConfig =
                    AppConfig(
                        outbox =
                            AppConfig.Outbox(
                                polling =
                                    AppConfig.Outbox.Polling(batchSize = batchSize),
                            ),
                    ),
                clock = clock,
            ),
    )

fun createRelayService(
    outboxRepository: MessageOutboxRepository,
    outboundMessagePort: OutboundMessagePort = mockk(relaxed = true),
    payloadRenderer: OutboxPayloadRenderer = mockk(relaxed = true),
    messageDispatcher: MessageDispatcher = mockk(relaxed = true),
    applicationEventPublisher: ApplicationEventPublisher = mockk(relaxed = true),
    clock: Clock = createFixedUtcClock(),
    relayTaskExecutor: Executor = Executor { command -> command.run() },
    appConfig: AppConfig = AppConfig(),
): SlackMessageRelayServiceImpl =
    SlackMessageRelayServiceImpl(
        outboxRepository = outboxRepository,
        outboundMessagePort = outboundMessagePort,
        payloadRenderer = payloadRenderer,
        messageDispatcher = messageDispatcher,
        retryService = RetryService(),
        applicationEventPublisher = applicationEventPublisher,
        relayTaskExecutor = relayTaskExecutor,
        clock = clock,
        appConfig = appConfig,
    )

fun MessageOutboxRepository.stubClaimLifecycle(renewed: Int = 1, completed: Int = 1, deferred: Int = 1) {
    every { renewClaim(eventId = any(), attemptCount = any(), now = any()) } returns renewed
    every { deferClaim(eventId = any(), attemptCount = any(), updatedAt = any()) } returns deferred
    every { completeClaim(eventId = any(), attemptCount = any(), status = any(), now = any()) } returns completed
}

fun MessageOutboxRepository.stubOutboxStatus(
    pendingCount: Long = 0L,
    stuckPendingCount: Long = 0L,
    oldestPendingCreatedAt: LocalDateTime? = null,
    inProgressCount: Long = 0L,
    stuckInProgressCount: Long = 0L,
    oldestInProgressUpdatedAt: LocalDateTime? = null,
    retryingCount: Long = 0L,
) {
    every { countPending() } returns pendingCount
    every { countPendingOlderThan(threshold = any()) } returns stuckPendingCount
    every { findOldestPendingCreatedAt() } returns oldestPendingCreatedAt
    every { countInProgress() } returns inProgressCount
    every { countInProgressOlderThan(threshold = any()) } returns stuckInProgressCount
    every { findOldestInProgressUpdatedAt() } returns oldestInProgressUpdatedAt
    every { countInProgressWithSendsAtLeast(sends = any()) } returns retryingCount
}
