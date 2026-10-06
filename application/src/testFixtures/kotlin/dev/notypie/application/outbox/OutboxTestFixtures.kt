package dev.notypie.application.outbox

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.service.relay.AccessBlockedTracker
import dev.notypie.application.service.relay.OutboxPayloadRenderer
import dev.notypie.application.service.relay.PollingMessageProcessor
import dev.notypie.application.service.relay.RenderedRow
import dev.notypie.application.service.relay.SlackMessageRelayServiceImpl
import dev.notypie.domain.command.dto.CommandBasicInfo
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.repository.outbox.Transport
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import dev.notypie.repository.outbox.schema.OutboxSchemaVersion
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executor

val DEFAULT_TEST_NOW: LocalDateTime = LocalDateTime.of(2026, 4, 28, 12, 0, 0)

fun createFixedUtcClock(now: LocalDateTime = DEFAULT_TEST_NOW): Clock =
    Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneId.of("UTC"))

fun createStubTransactionManager(): PlatformTransactionManager =
    mockk {
        every { getTransaction(any()) } returns mockk<TransactionStatus>(relaxed = true)
        every { commit(any()) } just Runs
        every { rollback(any()) } just Runs
    }

fun createOutboxRow(
    eventId: String,
    status: MessageStatus = MessageStatus.PENDING,
    createdAt: LocalDateTime = DEFAULT_TEST_NOW,
    attemptCount: Int = 0,
    sendCount: Int = 0,
    schemaVersion: Int = OutboxSchemaVersion.V2,
): OutboxMessage =
    mockk(relaxed = true) {
        every { this@mockk.eventId } returns eventId
        every { this@mockk.status } returns status.name
        every { this@mockk.createdAt } returns createdAt
        every { this@mockk.attemptCount } returns attemptCount
        every { this@mockk.sendCount } returns sendCount
        every { this@mockk.schemaVersion } returns schemaVersion
        every { this@mockk.transport } returns Transport.SLACK.name
    }

data class PollingProcessorFixture(
    val outboxRepository: MessageOutboxRepository,
    val relayService: SlackMessageRelayServiceImpl,
    val processor: PollingMessageProcessor,
)

fun createPollingProcessorFixture(
    batchSize: Int = 100,
    outboxRepository: MessageOutboxRepository = mockk(),
    relayService: SlackMessageRelayServiceImpl =
        mockk(relaxed = true) { every { reserveDispatchSlots(wanted = any()) } answers { firstArg() } },
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
    payloadRenderer: OutboxPayloadRenderer =
        mockk { every { render(row = any()) } returns RenderedRow(payload = mockk(relaxed = true), next = null) },
    messageDispatcher: MessageDispatcher = mockk(relaxed = true),
    applicationEventPublisher: ApplicationEventPublisher = mockk(relaxed = true),
    clock: Clock = createFixedUtcClock(),
    relayTaskExecutor: Executor = Executor { command -> command.run() },
    appConfig: AppConfig = AppConfig(),
    accessBlockedTracker: AccessBlockedTracker = AccessBlockedTracker(),
    transactionManager: PlatformTransactionManager = createStubTransactionManager(),
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
        accessBlockedTracker = accessBlockedTracker,
        transactionManager = transactionManager,
        appConfig = appConfig,
    )

fun OutboundMessagePort.captureChains(
    chains: MutableList<List<OutboundMessage>>,
    build: (OutboundMessage, CommandBasicInfo, List<OutboundMessage>) -> OutboxMessage = { _, _, _ ->
        createOutboxRow(eventId = UUID.randomUUID().toString())
    },
) {
    every { toRow(message = any(), basicInfo = any(), transport = any(), continuation = any()) } answers {
        val continuation = arg<List<OutboundMessage>>(n = 3)
        chains += listOf(firstArg<OutboundMessage>()) + continuation
        build(firstArg(), secondArg(), continuation)
    }
}

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

data class OutboxStatusRow(
    val status: MessageStatus,
    val createdAt: LocalDateTime = DEFAULT_TEST_NOW,
    val updatedAt: LocalDateTime = DEFAULT_TEST_NOW,
    val sendCount: Int = 0,
)

fun createOutboxRepositoryOver(rows: List<OutboxStatusRow>): MessageOutboxRepository {
    val pending = rows.filter { it.status == MessageStatus.PENDING }
    val inFlight = rows.filter { it.status == MessageStatus.IN_PROGRESS }
    return mockk {
        every { countPending() } returns pending.size.toLong()
        every { countPendingOlderThan(threshold = any()) } answers
            { pending.count { it.createdAt < firstArg<LocalDateTime>() }.toLong() }
        every { findOldestPendingCreatedAt() } returns pending.minOfOrNull { it.createdAt }
        every { countInProgress() } returns inFlight.size.toLong()
        every { countInProgressOlderThan(threshold = any()) } answers
            { inFlight.count { it.updatedAt < firstArg<LocalDateTime>() }.toLong() }
        every { findOldestInProgressUpdatedAt() } returns inFlight.minOfOrNull { it.updatedAt }
        every { countInProgressWithSendsAtLeast(sends = any()) } answers
            { inFlight.count { it.sendCount >= firstArg<Int>() }.toLong() }
    }
}
