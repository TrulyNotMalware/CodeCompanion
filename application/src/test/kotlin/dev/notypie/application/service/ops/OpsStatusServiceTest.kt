package dev.notypie.application.service.ops

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.outbox.createStubTransactionManager
import dev.notypie.application.outbox.stubOutboxStatus
import dev.notypie.application.service.meeting.createH2TransactionManager
import dev.notypie.application.service.meeting.failInsideParticipatingTx
import dev.notypie.application.service.relay.AccessBlockedTracker
import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.StatusReportPayload
import dev.notypie.domain.command.entity.event.StatusReportRequestEvent
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.domain.command.outbound.UserRef
import dev.notypie.impl.command.event.createSendSlackMessageEvent
import dev.notypie.repository.cve.CveCollectLedgerRepository
import dev.notypie.repository.cve.CveEventRepository
import dev.notypie.repository.cve.CveTopicRepository
import dev.notypie.repository.cve.schema.CveSummaryStatus
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

class OpsStatusServiceTest :
    BehaviorSpec({
        val now = LocalDateTime.of(2026, 4, 29, 12, 0, 0)
        val clock = createFixedUtcClock(now = now)

        given("OpsStatusService.handleStatusReport") {
            val outboxRepository = mockk<MessageOutboxRepository>()
            val accessBlockedTracker = AccessBlockedTracker()
            val stager = mockk<OutboundMessageStager>()
            val eventPublisher = mockk<EventPublisher>(relaxed = true)
            val service =
                OpsStatusService(
                    outboxRepository = outboxRepository,
                    outboundStager = stager,
                    eventPublisher = eventPublisher,
                    cveTopicRepository = mockk(relaxed = true),
                    cveEventRepository = mockk(relaxed = true),
                    cveCollectLedgerRepository = mockk(relaxed = true),
                    accessBlockedTracker = accessBlockedTracker,
                    clock = clock,
                    appConfig =
                        AppConfig(
                            outbox = AppConfig.Outbox(health = AppConfig.Outbox.Health(stuckThresholdSeconds = 300L)),
                        ),
                    transactionManager = createStubTransactionManager(),
                )

            val basic = createCommandBasicInfo()
            val event =
                StatusReportRequestEvent(
                    idempotencyKey = basic.idempotencyKey,
                    payload = StatusReportPayload(responseBasicInfo = basic),
                    type = CommandDetailType.STATUS_REPORT,
                )
            val outboundStub =
                createSendSlackMessageEvent(
                    commandDetailType = CommandDetailType.STATUS_REPORT,
                    idempotencyKey = basic.idempotencyKey,
                )

            `when`("everything is healthy (no PENDING, no IN_PROGRESS)") {
                outboxRepository.stubOutboxStatus()

                val captured = slot<OutboundMessage>()
                every { stager.stage(message = capture(captured), basicInfo = any()) } returns outboundStub
                val publishedQueue = slot<EventQueue<CommandEvent<EventPayload>>>()
                every { eventPublisher.publishEvent(events = capture(publishedQueue)) } returns Unit

                service.handleStatusReport(event = event)

                then("the rendered text reports zero counts and UP health") {
                    val reply = captured.captured as OutboundMessage.Ephemeral
                    val body = (reply.content as MessageContent.Text).markdown
                    body shouldContain "*Pending:* 0"
                    body shouldContain "*In-flight:* 0"
                    body shouldContain "UP"
                }

                then("the report is an ephemeral to the requester with the status headline and detail type") {
                    val reply = captured.captured.shouldBeInstanceOf<OutboundMessage.Ephemeral>()
                    reply.target shouldBe ConversationTarget(id = basic.channel)
                    reply.recipient shouldBe UserRef(id = basic.publisherId)
                    reply.detailType shouldBe CommandDetailType.STATUS_REPORT
                    reply.content.shouldBeInstanceOf<MessageContent.Text>().headline shouldBe
                        "CodeCompanion — outbox status"
                }

                then("the staged reply is published once") {
                    publishedQueue.captured.toList().single() shouldBe outboundStub
                }
            }

            `when`("there are stuck PENDING and IN_PROGRESS rows") {
                outboxRepository.stubOutboxStatus(
                    pendingCount = 7L,
                    stuckPendingCount = 2L,
                    oldestPendingCreatedAt = now.minusSeconds(900L),
                    inProgressCount = 1L,
                    stuckInProgressCount = 1L,
                    oldestInProgressUpdatedAt = now.minusSeconds(600L),
                )

                val captured = slot<OutboundMessage>()
                every { stager.stage(message = capture(captured), basicInfo = any()) } returns outboundStub
                every { eventPublisher.publishEvent(events = any()) } returns Unit

                service.handleStatusReport(event = event)

                then("the report surfaces stuck counts and DOWN health") {
                    val reply = captured.captured as OutboundMessage.Ephemeral
                    val body = (reply.content as MessageContent.Text).markdown
                    body shouldContain "*Pending:* 7 (oldest 900s ago, stuck 2)"
                    body shouldContain "*In-flight:* 1 (oldest 600s ago, stuck 1)"
                    body shouldContain "DOWN"
                }
            }

            `when`("a fresh in-flight row keeps being re-sent") {
                outboxRepository.stubOutboxStatus(inProgressCount = 1L, retryingCount = 1L)

                val captured = slot<OutboundMessage>()
                every { stager.stage(message = capture(captured), basicInfo = any()) } returns outboundStub
                every { eventPublisher.publishEvent(events = any()) } returns Unit

                service.handleStatusReport(event = event)

                then("the report shows the retrying count and DOWN, as the actuator indicator does") {
                    val body =
                        ((captured.captured as OutboundMessage.Ephemeral).content as MessageContent.Text)
                            .markdown
                    body shouldContain "*Retrying:* 1 (sent at least 3x, still in flight)"
                    body shouldContain "DOWN"
                }
            }

            `when`("the relay held a row because Slack refused the bot's access") {
                outboxRepository.stubOutboxStatus()
                accessBlockedTracker.record(at = clock.instant().minusSeconds(60L))

                val captured = slot<OutboundMessage>()
                every { stager.stage(message = capture(captured), basicInfo = any()) } returns outboundStub
                every { eventPublisher.publishEvent(events = any()) } returns Unit

                service.handleStatusReport(event = event)

                then("the report names the hold and says DOWN, as the actuator indicator does") {
                    val body =
                        ((captured.captured as OutboundMessage.Ephemeral).content as MessageContent.Text)
                            .markdown
                    body shouldContain "*Slack access blocked:* rows held, last at ${clock.instant().minusSeconds(60L)}"
                    body shouldContain "DOWN"
                }
            }

            `when`("the repository throws while reading metrics") {
                every { outboxRepository.countPending() } throws RuntimeException("db down")

                val captured = slot<OutboundMessage>()
                every { stager.stage(message = capture(captured), basicInfo = any()) } returns outboundStub
                every { eventPublisher.publishEvent(events = any()) } returns Unit

                service.handleStatusReport(event = event)

                then("the listener still publishes a friendly fallback instead of crashing") {
                    val reply = captured.captured as OutboundMessage.Ephemeral
                    val body = (reply.content as MessageContent.Text).markdown
                    body shouldContain "Failed to read outbox status"
                }
            }

            `when`("the CVE feature is disabled (the default)") {
                outboxRepository.stubOutboxStatus()

                val captured = slot<OutboundMessage>()
                every { stager.stage(message = capture(captured), basicInfo = any()) } returns outboundStub
                every { eventPublisher.publishEvent(events = any()) } returns Unit

                service.handleStatusReport(event = event)

                then("no CVE section is rendered") {
                    val body =
                        ((captured.captured as OutboundMessage.Ephemeral).content as MessageContent.Text)
                            .markdown
                    body shouldNotContain "CVE"
                }
            }
        }

        given("a transactional outbox read that fails while the mention's transaction is open") {
            val transactionManager = createH2TransactionManager()
            val outboxRepository = mockk<MessageOutboxRepository>()
            every { outboxRepository.countPending() } answers {
                transactionManager.failInsideParticipatingTx(exception = IllegalStateException("db down"))
            }
            val basic = createCommandBasicInfo()
            val stager = mockk<OutboundMessageStager>()
            val captured = slot<OutboundMessage>()
            every { stager.stage(message = capture(captured), basicInfo = any()) } returns
                createSendSlackMessageEvent(
                    commandDetailType = CommandDetailType.STATUS_REPORT,
                    idempotencyKey = basic.idempotencyKey,
                )
            val service =
                OpsStatusService(
                    outboxRepository = outboxRepository,
                    outboundStager = stager,
                    eventPublisher = mockk(relaxed = true),
                    cveTopicRepository = mockk(),
                    cveEventRepository = mockk(),
                    cveCollectLedgerRepository = mockk(),
                    accessBlockedTracker = AccessBlockedTracker(),
                    clock = clock,
                    appConfig = AppConfig(),
                    transactionManager = transactionManager,
                )
            val event =
                StatusReportRequestEvent(
                    idempotencyKey = basic.idempotencyKey,
                    payload = StatusReportPayload(responseBasicInfo = basic),
                    type = CommandDetailType.STATUS_REPORT,
                )

            `when`("the status report is handled inside that transaction") {
                then("the caller's transaction still commits the fallback reply") {
                    shouldNotThrowAny {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            service.handleStatusReport(event = event)
                        }
                    }
                    ((captured.captured as OutboundMessage.Ephemeral).content as MessageContent.Text)
                        .markdown shouldBe "Failed to read outbox status. Check application logs."
                }
            }
        }

        given("the CVE feature is enabled") {
            val outboxRepository = mockk<MessageOutboxRepository>()
            val stager = mockk<OutboundMessageStager>()
            val eventPublisher = mockk<EventPublisher>(relaxed = true)
            val cveTopicRepository = mockk<CveTopicRepository>()
            val cveEventRepository = mockk<CveEventRepository>()
            val cveCollectLedgerRepository = mockk<CveCollectLedgerRepository>()
            val service =
                OpsStatusService(
                    outboxRepository = outboxRepository,
                    outboundStager = stager,
                    eventPublisher = eventPublisher,
                    cveTopicRepository = cveTopicRepository,
                    cveEventRepository = cveEventRepository,
                    cveCollectLedgerRepository = cveCollectLedgerRepository,
                    accessBlockedTracker = AccessBlockedTracker(),
                    clock = clock,
                    appConfig =
                        AppConfig(
                            outbox = AppConfig.Outbox(health = AppConfig.Outbox.Health(stuckThresholdSeconds = 300L)),
                            cve = AppConfig.Cve(enabled = true),
                            ai = AppConfig.Ai(maxRetries = 5),
                        ),
                    transactionManager = createStubTransactionManager(),
                )

            val basic = createCommandBasicInfo()
            val event =
                StatusReportRequestEvent(
                    idempotencyKey = basic.idempotencyKey,
                    payload = StatusReportPayload(responseBasicInfo = basic),
                    type = CommandDetailType.STATUS_REPORT,
                )
            val outboundStub =
                createSendSlackMessageEvent(
                    commandDetailType = CommandDetailType.STATUS_REPORT,
                    idempotencyKey = basic.idempotencyKey,
                )

            fun stubCve() {
                outboxRepository.stubOutboxStatus()
                every { cveTopicRepository.countActive() } returns 3L
                every { cveEventRepository.countByStatus(status = CveSummaryStatus.PENDING) } returns 4L
                every { cveEventRepository.countByStatus(status = CveSummaryStatus.SUMMARIZING) } returns 1L
                every { cveEventRepository.countFailedRetryable(maxRetries = 5) } returns 2L
                every { cveEventRepository.countDeadLetter(maxRetries = 5) } returns 1L
            }

            `when`("there is a collect-ledger window") {
                stubCve()
                every { cveCollectLedgerRepository.latestWindowStart() } returns LocalDateTime.of(2026, 7, 14, 9, 30)

                val captured = slot<OutboundMessage>()
                every { stager.stage(message = capture(captured), basicInfo = any()) } returns outboundStub
                every { eventPublisher.publishEvent(events = any()) } returns Unit

                service.handleStatusReport(event = event)

                then("the CVE section reports topics, event counts split by status, and the last window") {
                    val body =
                        ((captured.captured as OutboundMessage.Ephemeral).content as MessageContent.Text)
                            .markdown
                    body shouldContain "*CVE topics:* 3 active"
                    body shouldContain "*CVE events:* 4 pending, 1 summarizing, 2 failed (retryable), 1 dead-letter"
                    body shouldContain "*CVE last collect window:* 2026-07-14 09:30"
                    body shouldContain "*Pending:* 0"
                }
            }

            `when`("nothing was ever collected") {
                stubCve()
                every { cveCollectLedgerRepository.latestWindowStart() } returns null

                val captured = slot<OutboundMessage>()
                every { stager.stage(message = capture(captured), basicInfo = any()) } returns outboundStub
                every { eventPublisher.publishEvent(events = any()) } returns Unit

                service.handleStatusReport(event = event)

                then("the last collect window reads never") {
                    val body =
                        ((captured.captured as OutboundMessage.Ephemeral).content as MessageContent.Text)
                            .markdown
                    body shouldContain "*CVE last collect window:* never"
                }
            }
        }
    })
