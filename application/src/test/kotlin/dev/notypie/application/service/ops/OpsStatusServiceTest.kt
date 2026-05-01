package dev.notypie.application.service.ops

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.createSendSlackMessageEvent
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.entity.event.StatusReportPayload
import dev.notypie.domain.command.entity.event.StatusReportRequestEvent
import dev.notypie.impl.command.SlackApiEventConstructor
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class OpsStatusServiceTest :
    BehaviorSpec({
        val now = LocalDateTime.of(2026, 4, 29, 12, 0, 0)
        val zoneId = ZoneId.of("UTC")
        val clock = Clock.fixed(now.toInstant(ZoneOffset.UTC), zoneId)

        given("OpsStatusService.handleStatusReport") {
            val outboxRepository = mockk<MessageOutboxRepository>()
            val slackEventBuilder = mockk<SlackApiEventConstructor>()
            val eventPublisher = mockk<EventPublisher>(relaxed = true)
            val service =
                OpsStatusService(
                    outboxRepository = outboxRepository,
                    slackEventBuilder = slackEventBuilder,
                    eventPublisher = eventPublisher,
                    clock = clock,
                    stuckThresholdSeconds = 300L,
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
                every { outboxRepository.countPending() } returns 0L
                every { outboxRepository.countPendingOlderThan(threshold = any()) } returns 0L
                every { outboxRepository.findOldestPendingCreatedAt() } returns null
                every { outboxRepository.countInProgress() } returns 0L
                every { outboxRepository.countInProgressOlderThan(threshold = any()) } returns 0L
                every { outboxRepository.findOldestInProgressUpdatedAt() } returns null

                val captured = slot<String>()
                every {
                    slackEventBuilder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        simpleString = capture(captured),
                    )
                } returns outboundStub
                val publishedQueue = slot<EventQueue<CommandEvent<EventPayload>>>()
                every { eventPublisher.publishEvent(events = capture(publishedQueue)) } returns Unit

                service.handleStatusReport(event = event)

                then("the rendered text reports zero counts and UP health") {
                    captured.captured shouldContain "*Pending:* 0"
                    captured.captured shouldContain "*In-flight:* 0"
                    captured.captured shouldContain "UP"
                }

                then("the rendered text is published as a single channel message") {
                    publishedQueue.captured.toList().single() shouldBe outboundStub
                }
            }

            `when`("there are stuck PENDING and IN_PROGRESS rows") {
                every { outboxRepository.countPending() } returns 7L
                every { outboxRepository.countPendingOlderThan(threshold = any()) } returns 2L
                every { outboxRepository.findOldestPendingCreatedAt() } returns now.minusSeconds(900L)
                every { outboxRepository.countInProgress() } returns 1L
                every { outboxRepository.countInProgressOlderThan(threshold = any()) } returns 1L
                every { outboxRepository.findOldestInProgressUpdatedAt() } returns now.minusSeconds(600L)

                val captured = slot<String>()
                every {
                    slackEventBuilder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        simpleString = capture(captured),
                    )
                } returns outboundStub
                every { eventPublisher.publishEvent(events = any()) } returns Unit

                service.handleStatusReport(event = event)

                then("the report surfaces stuck counts and DOWN health") {
                    captured.captured shouldContain "*Pending:* 7 (oldest 900s ago, stuck 2)"
                    captured.captured shouldContain "*In-flight:* 1 (oldest 600s ago, stuck 1)"
                    captured.captured shouldContain "DOWN"
                }
            }

            `when`("the repository throws while reading metrics") {
                every { outboxRepository.countPending() } throws RuntimeException("db down")

                val captured = slot<String>()
                every {
                    slackEventBuilder.simpleTextRequest(
                        commandDetailType = any(),
                        headLineText = any(),
                        commandBasicInfo = any(),
                        simpleString = capture(captured),
                    )
                } returns outboundStub
                every { eventPublisher.publishEvent(events = any()) } returns Unit

                service.handleStatusReport(event = event)

                then("the listener still publishes a friendly fallback instead of crashing") {
                    captured.captured shouldContain "Failed to read outbox status"
                }
            }
        }
    })
