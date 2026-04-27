package dev.notypie.impl.command

import dev.notypie.domain.TEST_BOT_TOKEN
import dev.notypie.domain.command.createActionEventPayloadContents
import dev.notypie.domain.command.createPostEventPayloadContents
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.event.MessageType
import dev.notypie.domain.history.entity.Status
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.OutboxMessage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.retry.RetryTemplate
import java.util.UUID

class ApplicationMessageDispatcherTest :
    BehaviorSpec({
        val outboxRepository = mockk<MessageOutboxRepository>()
        val outboxMessage = mockk<OutboxMessage>(relaxed = true)

        val dispatcher =
            ApplicationMessageDispatcher(
                botToken = TEST_BOT_TOKEN,
                applicationEventPublisher = mockk<ApplicationEventPublisher>(relaxed = true),
                retryService = RetryService(retryTemplate = RetryTemplate()),
                outboxRepository = outboxRepository,
            )

        val testIdempotencyKey = UUID.randomUUID()

        val postEvent =
            createPostEventPayloadContents(
                commandDetailType = CommandDetailType.SIMPLE_TEXT,
                idempotencyKey = testIdempotencyKey,
            )

        val actionEvent =
            createActionEventPayloadContents(
                commandDetailType = CommandDetailType.SIMPLE_TEXT,
                idempotencyKey = testIdempotencyKey,
                body = """{"text":"hello"}""",
            )

        beforeEach {
            clearMocks(outboxRepository)
            every { outboxRepository.save(any()) } returns outboxMessage
        }

        given("dispatch PostEventPayloadContents") {
            `when`("a valid post event is dispatched") {
                then("saves the event to the outbox") {
                    dispatcher.dispatch(
                        event = postEvent,
                        commandType = CommandType.SIMPLE,
                    )
                    verify(exactly = 1) { outboxRepository.save(any()) }
                }

                then("returns CommandOutput with IN_PROGRESSED status") {
                    val result =
                        dispatcher.dispatch(
                            event = postEvent,
                            commandType = CommandType.SIMPLE,
                        )
                    result.ok shouldBe true
                    result.status shouldBe Status.IN_PROGRESSED
                    result.commandType shouldBe CommandType.SIMPLE
                }

                then("CommandOutput fields match the dispatched event") {
                    val result =
                        dispatcher.dispatch(
                            event = postEvent,
                            commandType = CommandType.SIMPLE,
                        )
                    result.apiAppId shouldBe postEvent.apiAppId
                    result.channel shouldBe postEvent.channel
                    result.publisherId shouldBe postEvent.publisherId
                    result.idempotencyKey shouldBe postEvent.idempotencyKey
                    result.commandDetailType shouldBe postEvent.commandDetailType
                }
            }
        }

        given("dispatch ActionEventPayloadContents") {
            `when`("a valid action event is dispatched") {
                then("saves the event to the outbox") {
                    dispatcher.dispatch(
                        event = actionEvent,
                        commandType = CommandType.RESPONSE,
                    )
                    verify(exactly = 1) { outboxRepository.save(any()) }
                }

                then("returns CommandOutput with IN_PROGRESSED status") {
                    val result =
                        dispatcher.dispatch(
                            event = actionEvent,
                            commandType = CommandType.RESPONSE,
                        )
                    result.ok shouldBe true
                    result.status shouldBe Status.IN_PROGRESSED
                    result.commandType shouldBe CommandType.RESPONSE
                }

                then("CommandOutput fields match the dispatched event") {
                    val result =
                        dispatcher.dispatch(
                            event = actionEvent,
                            commandType = CommandType.RESPONSE,
                        )
                    result.apiAppId shouldBe actionEvent.apiAppId
                    result.channel shouldBe actionEvent.channel
                    result.publisherId shouldBe actionEvent.publisherId
                    result.idempotencyKey shouldBe actionEvent.idempotencyKey
                    result.commandDetailType shouldBe actionEvent.commandDetailType
                }
            }
        }

        given("dispatch PostEventPayloadContents with EPHEMERAL_MESSAGE type") {
            val ephemeralEvent =
                postEvent.copy(
                    messageType = MessageType.EPHEMERAL_MESSAGE,
                    idempotencyKey = UUID.randomUUID(),
                )

            `when`("an ephemeral event is dispatched") {
                then("saves to outbox and returns IN_PROGRESSED status") {
                    val result =
                        dispatcher.dispatch(
                            event = ephemeralEvent,
                            commandType = CommandType.SIMPLE,
                        )
                    verify(exactly = 1) { outboxRepository.save(any()) }
                    result.status shouldBe Status.IN_PROGRESSED
                }
            }
        }
    })
