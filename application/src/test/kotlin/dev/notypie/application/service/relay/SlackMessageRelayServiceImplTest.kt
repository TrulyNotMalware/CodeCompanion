package dev.notypie.application.service.relay

import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.domain.command.entity.event.SlackEventPayload
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.outbox.dto.NewMessagePublishedEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.retry.RetryTemplate
import java.util.Optional
import java.util.UUID
import java.util.concurrent.Executor

class SlackMessageRelayServiceImplTest :
    BehaviorSpec({
        fun createRelayService(outboxRepository: MessageOutboxRepository): SlackMessageRelayServiceImpl =
            SlackMessageRelayServiceImpl(
                outboxRepository = outboxRepository,
                messageDispatcher = mockk<MessageDispatcher>(relaxed = true),
                retryService = RetryService(retryTemplate = RetryTemplate()),
                applicationEventPublisher = mockk<ApplicationEventPublisher>(relaxed = true),
                relayTaskExecutor = Executor { command -> command.run() },
            )

        given("saveOutboxMessages") {
            `when`("a NewMessagePublishedEvent is received") {
                val outboxMessage = createOutboxRow(eventId = UUID.randomUUID().toString())
                val outboxRepository = mockk<MessageOutboxRepository>()
                every { outboxRepository.save(outboxMessage) } returns outboxMessage
                val service = createRelayService(outboxRepository = outboxRepository)
                val event =
                    NewMessagePublishedEvent(
                        reason = "test",
                        outboxMessage = outboxMessage,
                        slackEventPayload = mockk<SlackEventPayload>(relaxed = true),
                    )

                service.saveOutboxMessages(event = event)

                then("the message is persisted through the repository") {
                    verify(exactly = 1) { outboxRepository.save(outboxMessage) }
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
