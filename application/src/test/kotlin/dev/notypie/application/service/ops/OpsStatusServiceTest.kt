package dev.notypie.application.service.ops

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.outbox.stubOutboxStatus
import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.StatusReportPayload
import dev.notypie.domain.command.entity.event.StatusReportRequestEvent
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.command.event.createSendSlackMessageEvent
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDateTime

class OpsStatusServiceTest :
    BehaviorSpec({
        val now = LocalDateTime.of(2026, 4, 29, 12, 0, 0)
        val clock = createFixedUtcClock(now = now)

        given("OpsStatusService.handleStatusReport") {
            val outboxRepository = mockk<MessageOutboxRepository>()
            val stager = mockk<OutboundMessageStager>()
            val eventPublisher = mockk<EventPublisher>(relaxed = true)
            val service =
                OpsStatusService(
                    outboxRepository = outboxRepository,
                    outboundStager = stager,
                    eventPublisher = eventPublisher,
                    clock = clock,
                    appConfig =
                        AppConfig(
                            outbox = AppConfig.Outbox(health = AppConfig.Outbox.Health(stuckThresholdSeconds = 300L)),
                        ),
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
                    val channelMessage = captured.captured as OutboundMessage.ChannelMessage
                    val body = (channelMessage.content as MessageContent.Text).markdown
                    body shouldContain "*Pending:* 0"
                    body shouldContain "*In-flight:* 0"
                    body shouldContain "UP"
                }

                then("the rendered text is published as a single channel message") {
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
                    val channelMessage = captured.captured as OutboundMessage.ChannelMessage
                    val body = (channelMessage.content as MessageContent.Text).markdown
                    body shouldContain "*Pending:* 7 (oldest 900s ago, stuck 2)"
                    body shouldContain "*In-flight:* 1 (oldest 600s ago, stuck 1)"
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
                    val channelMessage = captured.captured as OutboundMessage.ChannelMessage
                    val body = (channelMessage.content as MessageContent.Text).markdown
                    body shouldContain "Failed to read outbox status"
                }
            }
        }
    })
