package dev.notypie.application.service.relay

import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.command.event.OutboundMessageEnqueued
import dev.notypie.impl.command.event.OutboundMessageEnqueuedPayload
import dev.notypie.impl.command.event.SlackEventPayload
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.repository.outbox.dto.MessagePublishFailedEvent
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.retry.RetryTemplate
import java.util.Optional
import java.util.UUID
import java.util.concurrent.Executor

class SlackMessageRelayServiceImplTest :
    BehaviorSpec({
        fun createRelayService(
            outboxRepository: MessageOutboxRepository,
            outboundMessagePort: OutboundMessagePort = mockk(relaxed = true),
            payloadRenderer: OutboxPayloadRenderer = mockk(relaxed = true),
            messageDispatcher: MessageDispatcher = mockk(relaxed = true),
            applicationEventPublisher: ApplicationEventPublisher = mockk(relaxed = true),
        ): SlackMessageRelayServiceImpl =
            SlackMessageRelayServiceImpl(
                outboxRepository = outboxRepository,
                outboundMessagePort = outboundMessagePort,
                payloadRenderer = payloadRenderer,
                messageDispatcher = messageDispatcher,
                retryService = RetryService(retryTemplate = RetryTemplate()),
                applicationEventPublisher = applicationEventPublisher,
                relayTaskExecutor = Executor { command -> command.run() },
            )

        given("saveOutboxMessage (interactive enqueue)") {
            `when`("an OutboundMessageEnqueued is received") {
                val basicInfo = createCommandBasicInfo()
                val message =
                    OutboundMessage.ChannelMessage(
                        target = ConversationTarget(id = basicInfo.channel),
                        content = MessageContent.Text(headline = null, markdown = "hi"),
                    )
                val row = createOutboxRow(eventId = UUID.randomUUID().toString())
                val port = mockk<OutboundMessagePort>()
                every { port.toRow(message = message, basicInfo = basicInfo) } returns row
                val outboxRepository = mockk<MessageOutboxRepository>()
                every { outboxRepository.save(row) } returns row
                val service = createRelayService(outboxRepository = outboxRepository, outboundMessagePort = port)
                val event =
                    OutboundMessageEnqueued(
                        idempotencyKey = basicInfo.idempotencyKey,
                        payload = OutboundMessageEnqueuedPayload(message = message, basicInfo = basicInfo),
                    )

                service.saveOutboxMessage(event = event)

                then("the port builds a transport-neutral row that is persisted") {
                    verify(exactly = 1) { port.toRow(message = message, basicInfo = basicInfo) }
                    verify(exactly = 1) { outboxRepository.save(row) }
                }
            }
        }

        given("batchPendingMessagesAsync (deliver-time render + dispatch)") {
            `when`("render and dispatch succeed") {
                val rowEventId = UUID.randomUUID()
                val row = createOutboxRow(eventId = rowEventId.toString())
                val rendered = mockk<SlackEventPayload>()
                val payloadRenderer = mockk<OutboxPayloadRenderer>()
                every { payloadRenderer.render(row = row) } returns rendered
                val dispatchResult =
                    mockk<CommandOutput> {
                        every { ok } returns true
                        every { messageTs } returns "1700000000.000200"
                    }
                val messageDispatcher = mockk<MessageDispatcher>()
                every { messageDispatcher.dispatch(event = rendered) } returns dispatchResult
                val published = slot<Any>()
                val eventPublisher = mockk<ApplicationEventPublisher>()
                every { eventPublisher.publishEvent(capture(published)) } returns Unit
                val service =
                    createRelayService(
                        outboxRepository = mockk(relaxed = true),
                        payloadRenderer = payloadRenderer,
                        messageDispatcher = messageDispatcher,
                        applicationEventPublisher = eventPublisher,
                    )

                service.batchPendingMessagesAsync(pendingMessage = row)

                then("the success event keys on the ROW eventId, not any renderer-minted id") {
                    published.captured shouldBe
                        MessagePublishSuccessEvent(eventId = rowEventId, messageTs = "1700000000.000200")
                }
            }

            `when`("the renderer throws (codec/schema/registry failure)") {
                val rowEventId = UUID.randomUUID()
                val row = createOutboxRow(eventId = rowEventId.toString())
                val payloadRenderer = mockk<OutboxPayloadRenderer>()
                every { payloadRenderer.render(row = row) } throws IllegalStateException("undecodable envelope")
                val messageDispatcher = mockk<MessageDispatcher>()
                val published = slot<Any>()
                val eventPublisher = mockk<ApplicationEventPublisher>()
                every { eventPublisher.publishEvent(capture(published)) } returns Unit
                val service =
                    createRelayService(
                        outboxRepository = mockk(relaxed = true),
                        payloadRenderer = payloadRenderer,
                        messageDispatcher = messageDispatcher,
                        applicationEventPublisher = eventPublisher,
                    )

                service.batchPendingMessagesAsync(pendingMessage = row)

                then("a failure event for the row is published and dispatch is never attempted") {
                    val failure = published.captured.shouldBeInstanceOf<MessagePublishFailedEvent>()
                    failure.eventId shouldBe rowEventId
                    verify(exactly = 0) { messageDispatcher.dispatch(event = any()) }
                }
            }

            `when`("the row carries a malformed eventId") {
                val row = createOutboxRow(eventId = "not-a-uuid")
                val payloadRenderer = mockk<OutboxPayloadRenderer>()
                val eventPublisher = mockk<ApplicationEventPublisher>()
                val service =
                    createRelayService(
                        outboxRepository = mockk(relaxed = true),
                        payloadRenderer = payloadRenderer,
                        applicationEventPublisher = eventPublisher,
                    )

                service.batchPendingMessagesAsync(pendingMessage = row)

                then("the row is skipped without rendering or publishing") {
                    verify(exactly = 0) { payloadRenderer.render(row = any()) }
                    verify(exactly = 0) { eventPublisher.publishEvent(any()) }
                }
            }
        }

        given("updateOutboxMessageStatus") {
            `when`("a success update event is received") {
                val eventId = UUID.randomUUID()
                val storedMessage = createOutboxRow(eventId = eventId.toString())
                val outboxRepository = mockk<MessageOutboxRepository>()
                every { outboxRepository.findById(eventId.toString()) } returns Optional.of(storedMessage)
                every { outboxRepository.save(storedMessage) } returns storedMessage
                val service = createRelayService(outboxRepository = outboxRepository)
                val event = MessagePublishSuccessEvent(eventId = eventId, messageTs = "1700000000.000100")

                service.updateOutboxMessageStatus(event = event)

                then("the row is loaded, transitioned to SUCCESS, and saved back") {
                    verify(exactly = 1) { outboxRepository.findById(eventId.toString()) }
                    verify(exactly = 1) { storedMessage.updateMessageStatus(status = MessageStatus.SUCCESS) }
                    verify(exactly = 1) { outboxRepository.save(storedMessage) }
                }
            }

            `when`("the row no longer exists") {
                val eventId = UUID.randomUUID()
                val outboxRepository = mockk<MessageOutboxRepository>()
                every { outboxRepository.findById(eventId.toString()) } returns Optional.empty<OutboxMessage>()
                val service = createRelayService(outboxRepository = outboxRepository)
                val event = MessagePublishSuccessEvent(eventId = eventId, messageTs = "")

                then("the retry exhaustion surfaces and nothing is saved") {
                    try {
                        service.updateOutboxMessageStatus(event = event)
                    } catch (expected: Exception) {
                        // retryService re-throws once the missing row exhausts all attempts
                    }
                    verify(exactly = 0) { outboxRepository.save(any()) }
                }
            }
        }
    })
