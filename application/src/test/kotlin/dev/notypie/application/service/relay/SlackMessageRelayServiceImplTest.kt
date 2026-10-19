package dev.notypie.application.service.relay

import ch.qos.logback.classic.Level
import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.configurations.AsyncConfig
import dev.notypie.application.configurations.RELAY_UNCAUGHT_ERROR_LOGGER
import dev.notypie.application.outbox.DEFAULT_TEST_NOW
import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.application.outbox.createRelayService
import dev.notypie.application.outbox.createStubTransactionManager
import dev.notypie.application.outbox.stubClaimLifecycle
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.impl.command.ACCESS_BLOCKED_REASON
import dev.notypie.impl.command.OUTCOME_UNKNOWN_REASON
import dev.notypie.impl.command.RateLimitedOutput
import dev.notypie.impl.command.TRANSIENT_EXHAUSTED_REASON
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.command.event.OutboundMessageEnqueued
import dev.notypie.impl.command.event.OutboundMessageEnqueuedPayload
import dev.notypie.impl.command.event.SlackEventPayload
import dev.notypie.impl.command.event.createPostEventPayloadContents
import dev.notypie.impl.command.event.failOutput
import dev.notypie.impl.command.event.successOutput
import dev.notypie.logging.captureLogs
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundEnvelope
import dev.notypie.repository.outbox.OutboundMessageCodec
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.repository.outbox.Transport
import dev.notypie.repository.outbox.dto.MessageDeliveredEvent
import dev.notypie.repository.outbox.dto.MessagePublishFailedEvent
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.outbox.dto.OutboxUpdateEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import dev.notypie.repository.outbox.schema.OutboxSchemaVersion
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionSystemException
import java.time.Duration
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SlackMessageRelayServiceImplTest :
    BehaviorSpec({
        fun payload() = createPostEventPayloadContents(commandDetailType = CommandDetailType.SIMPLE_TEXT)

        fun delivered() = successOutput(payload = payload(), commandType = CommandType.EXTERNAL_API)

        fun dispatcherReturning(output: CommandOutput): MessageDispatcher =
            mockk { every { dispatch(event = any()) } returns output }

        fun appConfigWithBatchSize(batchSize: Int) =
            AppConfig(outbox = AppConfig.Outbox(polling = AppConfig.Outbox.Polling(batchSize = batchSize)))

        fun boundedPool(threads: Int, queue: Int) =
            ThreadPoolTaskExecutor().apply {
                corePoolSize = threads
                maxPoolSize = threads
                queueCapacity = queue
                setThreadNamePrefix("relay-test-")
                setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
                initialize()
            }

        fun newClaim() = OutboxClaim(row = createOutboxRow(eventId = UUID.randomUUID().toString()), attempt = 1)

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

            `when`("saving the row fails inside the command's transaction") {
                val basicInfo = createCommandBasicInfo()
                val message =
                    OutboundMessage.ChannelMessage(
                        target = ConversationTarget(id = basicInfo.channel),
                        content = MessageContent.Text(headline = null, markdown = "hi"),
                    )
                val row = createOutboxRow(eventId = UUID.randomUUID().toString())
                val port = mockk<OutboundMessagePort>()
                every { port.toRow(message = message, basicInfo = basicInfo) } returns row
                val failure = DataAccessResourceFailureException("connection lost")
                val outboxRepository = mockk<MessageOutboxRepository>()
                every { outboxRepository.save(row) } throws failure
                val service = createRelayService(outboxRepository = outboxRepository, outboundMessagePort = port)
                val event =
                    OutboundMessageEnqueued(
                        idempotencyKey = basicInfo.idempotencyKey,
                        payload = OutboundMessageEnqueuedPayload(message = message, basicInfo = basicInfo),
                    )

                then("the original failure propagates after one attempt, so the command rolls back") {
                    shouldThrow<DataAccessResourceFailureException> {
                        service.saveOutboxMessage(event = event)
                    } shouldBeSameInstanceAs failure
                    verify(exactly = 1) { outboxRepository.save(row) }
                }
            }
        }

        given("dispatchClaimed (deliver-time render + dispatch)") {
            `when`("render and dispatch succeed") {
                val rowEventId = UUID.randomUUID()
                val row = createOutboxRow(eventId = rowEventId.toString())
                val rendered = mockk<SlackEventPayload>()
                val payloadRenderer = mockk<OutboxPayloadRenderer>()
                every { payloadRenderer.render(row = row) } returns RenderedRow(payload = rendered, next = null)
                val dispatchResult =
                    mockk<CommandOutput> {
                        every { ok } returns true
                        every { messageTs } returns "1700000000.000200"
                        every { commandDetailType } returns CommandDetailType.STANDUP_SUMMARY
                    }
                val messageDispatcher = mockk<MessageDispatcher>()
                every { messageDispatcher.dispatch(event = rendered) } returns dispatchResult
                val published = mutableListOf<Any>()
                val eventPublisher = mockk<ApplicationEventPublisher>()
                every { eventPublisher.publishEvent(capture(published)) } returns Unit
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        payloadRenderer = payloadRenderer,
                        messageDispatcher = messageDispatcher,
                        applicationEventPublisher = eventPublisher,
                    )
                val deliveredEvent =
                    MessageDeliveredEvent(
                        eventId = rowEventId,
                        commandDetailType = CommandDetailType.STANDUP_SUMMARY,
                        messageTs = "1700000000.000200",
                    )
                val successEvent =
                    MessagePublishSuccessEvent(
                        eventId = rowEventId,
                        commandDetailType = CommandDetailType.STANDUP_SUMMARY,
                        messageTs = "1700000000.000200",
                    )

                service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 3))

                then("the lease is renewed and SUCCESS written for the claimed attempt with the clock's time") {
                    verify(exactly = 1) {
                        outboxRepository.renewClaim(
                            eventId = rowEventId.toString(),
                            attemptCount = 3,
                            now = DEFAULT_TEST_NOW,
                        )
                    }
                    verify(exactly = 1) {
                        outboxRepository.completeClaim(
                            eventId = rowEventId.toString(),
                            attemptCount = 3,
                            status = MessageStatus.SUCCESS.name,
                            now = DEFAULT_TEST_NOW,
                        )
                    }
                }

                then("both events key on the ROW eventId, not any renderer-minted id, and name what was sent") {
                    published shouldBe listOf(deliveredEvent, successEvent)
                }

                then("the delivery is announced before SUCCESS is written, the success event only after it") {
                    verifyOrder {
                        eventPublisher.publishEvent(deliveredEvent)
                        outboxRepository.completeClaim(
                            eventId = rowEventId.toString(),
                            attemptCount = 3,
                            status = MessageStatus.SUCCESS.name,
                            now = DEFAULT_TEST_NOW,
                        )
                        eventPublisher.publishEvent(successEvent)
                    }
                }
            }

            `when`("the claim was reclaimed by the recovery sweep while the task waited in the queue") {
                val row = createOutboxRow(eventId = UUID.randomUUID().toString())
                val payloadRenderer = mockk<OutboxPayloadRenderer>()
                val messageDispatcher = mockk<MessageDispatcher>()
                val eventPublisher = mockk<ApplicationEventPublisher>()
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle(renewed = 0)
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        payloadRenderer = payloadRenderer,
                        messageDispatcher = messageDispatcher,
                        applicationEventPublisher = eventPublisher,
                    )

                service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1))

                then("the stale worker neither sends nor writes a status") {
                    verify(exactly = 0) { payloadRenderer.render(row = any()) }
                    verify(exactly = 0) { messageDispatcher.dispatch(event = any()) }
                    verify(exactly = 0) {
                        outboxRepository.completeClaim(
                            eventId = any(),
                            attemptCount = any(),
                            status = any(),
                            now = any(),
                        )
                    }
                    verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
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
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        payloadRenderer = payloadRenderer,
                        messageDispatcher = messageDispatcher,
                        applicationEventPublisher = eventPublisher,
                    )

                service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1))

                then("FAILURE is written, a failure event is published and dispatch is never attempted") {
                    verify(exactly = 1) {
                        outboxRepository.completeClaim(
                            eventId = rowEventId.toString(),
                            attemptCount = 1,
                            status = MessageStatus.FAILURE.name,
                            now = DEFAULT_TEST_NOW,
                        )
                    }
                    val failure = published.captured.shouldBeInstanceOf<MessagePublishFailedEvent>()
                    failure.eventId shouldBe rowEventId
                    verify(exactly = 0) { messageDispatcher.dispatch(event = any()) }
                }
            }

            `when`("a newer owner already finished the row when this worker writes its result") {
                val eventId = UUID.randomUUID().toString()
                val row = createOutboxRow(eventId = eventId)
                val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle(completed = 0)
                val service =
                    createRelayService(outboxRepository = outboxRepository, applicationEventPublisher = eventPublisher)

                then("the conditional write is a no-op, nothing is thrown and the new owner is left to publish") {
                    shouldNotThrowAny { service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1)) }
                    verify(exactly = 1) {
                        outboxRepository.completeClaim(
                            eventId = eventId,
                            attemptCount = 1,
                            status = any(),
                            now = any(),
                        )
                    }
                    verify(exactly = 0) { eventPublisher.publishEvent(ofType<OutboxUpdateEvent>()) }
                }
            }

            `when`("a listener of the outcome event throws after SUCCESS was recorded") {
                val eventId = UUID.randomUUID().toString()
                val row = createOutboxRow(eventId = eventId)
                val eventPublisher = mockk<ApplicationEventPublisher>()
                every { eventPublisher.publishEvent(ofType<MessageDeliveredEvent>()) } returns Unit
                every { eventPublisher.publishEvent(ofType<OutboxUpdateEvent>()) } throws
                    IllegalStateException("standup ts write failed")
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher = dispatcherReturning(output = delivered()),
                        applicationEventPublisher = eventPublisher,
                    )

                then("the failure stays with the listener and the recorded status is written exactly once") {
                    shouldNotThrowAny { service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1)) }
                    verify(exactly = 1) {
                        outboxRepository.completeClaim(
                            eventId = eventId,
                            attemptCount = 1,
                            status = MessageStatus.SUCCESS.name,
                            now = any(),
                        )
                    }
                    verify(exactly = 1) { eventPublisher.publishEvent(ofType<MessagePublishSuccessEvent>()) }
                }
            }

            `when`("a listener of the delivery throws before the status write") {
                val eventId = UUID.randomUUID().toString()
                val row = createOutboxRow(eventId = eventId)
                val eventPublisher = mockk<ApplicationEventPublisher>()
                every { eventPublisher.publishEvent(ofType<MessageDeliveredEvent>()) } throws
                    IllegalStateException("ask ledger insert failed")
                every { eventPublisher.publishEvent(ofType<MessagePublishSuccessEvent>()) } returns Unit
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher = dispatcherReturning(output = delivered()),
                        applicationEventPublisher = eventPublisher,
                    )

                then("nothing is thrown, SUCCESS is still written once and the success event still follows") {
                    shouldNotThrowAny { service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1)) }
                    verifyOrder {
                        eventPublisher.publishEvent(ofType<MessageDeliveredEvent>())
                        outboxRepository.completeClaim(
                            eventId = eventId,
                            attemptCount = 1,
                            status = MessageStatus.SUCCESS.name,
                            now = any(),
                        )
                        eventPublisher.publishEvent(ofType<MessagePublishSuccessEvent>())
                    }
                    verify(exactly = 1) {
                        outboxRepository.completeClaim(
                            eventId = any(),
                            attemptCount = any(),
                            status = any(),
                            now = any(),
                        )
                    }
                }
            }

            `when`("Slack accepted the message but another owner recorded the row first") {
                val rowEventId = UUID.randomUUID()
                val row = createOutboxRow(eventId = rowEventId.toString())
                val published = mutableListOf<Any>()
                val eventPublisher = mockk<ApplicationEventPublisher>()
                every { eventPublisher.publishEvent(capture(published)) } returns Unit
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle(completed = 0)
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher =
                            dispatcherReturning(
                                output =
                                    successOutput(
                                        payload = payload(),
                                        commandType = CommandType.EXTERNAL_API,
                                        messageTs = "1700000000.000300",
                                    ),
                            ),
                        applicationEventPublisher = eventPublisher,
                    )

                service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1))

                then("the delivery was already announced, and no success event follows the lost status write") {
                    published shouldBe
                        listOf(
                            MessageDeliveredEvent(
                                eventId = rowEventId,
                                commandDetailType = CommandDetailType.SIMPLE_TEXT,
                                messageTs = "1700000000.000300",
                            ),
                        )
                    verify(exactly = 0) { outboxRepository.findById(any()) }
                }
            }

            `when`("the send is refused for good, has an unknown outcome, is rate-limited or fails transiently") {
                val published = mutableListOf<Any>()
                val eventPublisher = mockk<ApplicationEventPublisher>()
                every { eventPublisher.publishEvent(capture(published)) } returns Unit
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                val messageDispatcher = mockk<MessageDispatcher>()
                every { messageDispatcher.dispatch(event = any()) } returnsMany
                    listOf(
                        failOutput(event = payload(), reason = "channel_not_found"),
                        failOutput(event = payload(), reason = "$OUTCOME_UNKNOWN_REASON: read timed out"),
                        RateLimitedOutput(event = payload(), retryAfter = Duration.ofSeconds(30L)),
                        failOutput(event = payload(), reason = TRANSIENT_EXHAUSTED_REASON),
                    )
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher = messageDispatcher,
                        applicationEventPublisher = eventPublisher,
                    )

                repeat(times = 4) { service.dispatchClaimed(claim = newClaim()) }

                then("no delivery is announced: only the two recorded failures publish an event") {
                    verify(exactly = 4) { messageDispatcher.dispatch(event = any()) }
                    published.map { it::class } shouldBe
                        listOf(MessagePublishFailedEvent::class, MessagePublishFailedEvent::class)
                }
            }

            `when`("Slack rate-limits the send with a Retry-After") {
                val first = createOutboxRow(eventId = "00000000-0000-0000-0000-000000000001")
                val second = createOutboxRow(eventId = "7f3c1a52-9d4e-4b8a-a1f0-2c6d8e9b0a11")
                val deferredTo = mutableListOf<LocalDateTime>()
                val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                every {
                    outboxRepository.deferClaim(eventId = any(), attemptCount = 2, updatedAt = capture(deferredTo))
                } returns 1
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher =
                            dispatcherReturning(
                                output = RateLimitedOutput(event = payload(), retryAfter = Duration.ofSeconds(30L)),
                            ),
                        applicationEventPublisher = eventPublisher,
                    )

                service.dispatchClaimed(claim = OutboxClaim(row = first, attempt = 2))
                service.dispatchClaimed(claim = OutboxClaim(row = second, attempt = 2))

                then("each row is deferred past Retry-After at its own offset and nothing terminal is written") {
                    deferredTo.forEach { updatedAt ->
                        updatedAt shouldBeGreaterThanOrEqualTo DEFAULT_TEST_NOW.minusSeconds(270L)
                        updatedAt shouldBeLessThan DEFAULT_TEST_NOW.minusSeconds(150L)
                    }
                    deferredTo.distinct() shouldHaveSize 2
                    verify(exactly = 0) {
                        outboxRepository.completeClaim(
                            eventId = any(),
                            attemptCount = any(),
                            status = any(),
                            now = any(),
                        )
                    }
                    verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
                }
            }

            `when`("Slack asks for a long Retry-After, and for one that outlasts the give-up window") {
                val longRow = createOutboxRow(eventId = "00000000-0000-0000-0000-000000000002")
                val beyondRow = createOutboxRow(eventId = "00000000-0000-0000-0000-000000000003")
                val deferredTo = mutableListOf<LocalDateTime>()
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                every {
                    outboxRepository.deferClaim(eventId = any(), attemptCount = any(), updatedAt = capture(deferredTo))
                } returns 1
                val messageDispatcher = mockk<MessageDispatcher>()
                every { messageDispatcher.dispatch(event = any()) } returns
                    RateLimitedOutput(event = payload(), retryAfter = Duration.ofHours(2L)) andThen
                    RateLimitedOutput(event = payload(), retryAfter = Duration.ofHours(48L))
                val service =
                    createRelayService(outboxRepository = outboxRepository, messageDispatcher = messageDispatcher)

                service.dispatchClaimed(claim = OutboxClaim(row = longRow, attempt = 1))
                service.dispatchClaimed(claim = OutboxClaim(row = beyondRow, attempt = 1))

                then("the row waits the real Retry-After, but never past the 24 h bound measured from created_at") {
                    deferredTo[0] shouldBeGreaterThanOrEqualTo DEFAULT_TEST_NOW.plusSeconds(7_200L - 300L)
                    deferredTo[0] shouldBeLessThan DEFAULT_TEST_NOW.plusSeconds(7_200L + 120L - 300L)
                    deferredTo[1] shouldBe DEFAULT_TEST_NOW.plusHours(24L).minusSeconds(300L)
                }
            }

            `when`("the Retry-After is too large for date arithmetic") {
                val row = createOutboxRow(eventId = UUID.randomUUID().toString())
                val deferredTo = slot<LocalDateTime>()
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                every {
                    outboxRepository.deferClaim(eventId = any(), attemptCount = any(), updatedAt = capture(deferredTo))
                } returns 1
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher =
                            dispatcherReturning(
                                output =
                                    RateLimitedOutput(
                                        event = payload(),
                                        retryAfter = Duration.ofSeconds(Long.MAX_VALUE),
                                    ),
                            ),
                    )

                then("the row is still deferred to the 24 h bound instead of the dispatch throwing") {
                    shouldNotThrowAny { service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1)) }
                    deferredTo.captured shouldBe DEFAULT_TEST_NOW.plusHours(24L).minusSeconds(300L)
                }
            }

            `when`("Slack refuses the bot's token or workspace") {
                val row = createOutboxRow(eventId = UUID.randomUUID().toString())
                val deferredTo = slot<LocalDateTime>()
                val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                every {
                    outboxRepository.deferClaim(eventId = any(), attemptCount = 1, updatedAt = capture(deferredTo))
                } returns 1
                val tracker = AccessBlockedTracker()
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher =
                            dispatcherReturning(
                                output = failOutput(event = payload(), reason = ACCESS_BLOCKED_REASON),
                            ),
                        applicationEventPublisher = eventPublisher,
                        accessBlockedTracker = tracker,
                    )

                service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1))

                then("the row is held for the access-blocked wait instead of failing, and the hold is recorded") {
                    deferredTo.captured shouldBeGreaterThanOrEqualTo
                        DEFAULT_TEST_NOW.plus(ACCESS_BLOCKED_DEFER).minusSeconds(300L)
                    deferredTo.captured shouldBeLessThan
                        DEFAULT_TEST_NOW.plus(ACCESS_BLOCKED_DEFER).plus(RATE_LIMIT_SPREAD).minusSeconds(300L)
                    verify(exactly = 0) {
                        outboxRepository.completeClaim(
                            eventId = any(),
                            attemptCount = any(),
                            status = any(),
                            now = any(),
                        )
                    }
                    verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
                    tracker.lastBlockedAt() shouldBe createFixedUtcClock().instant()
                }
            }

            `when`("Slack rate-limits the send without a Retry-After") {
                val row = createOutboxRow(eventId = UUID.randomUUID().toString())
                val deferredTo = slot<LocalDateTime>()
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                every {
                    outboxRepository.deferClaim(eventId = any(), attemptCount = any(), updatedAt = capture(deferredTo))
                } returns 1
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher =
                            dispatcherReturning(output = RateLimitedOutput(event = payload(), retryAfter = null)),
                    )

                val warnings =
                    captureLogs(loggerName = SlackMessageRelayServiceImpl::class.java.packageName) {
                        service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1))
                    }.messagesAt(level = Level.WARN)

                then("it waits the default minute plus its spread") {
                    deferredTo.captured shouldBeGreaterThanOrEqualTo DEFAULT_TEST_NOW.minusSeconds(240L)
                    deferredTo.captured shouldBeLessThan DEFAULT_TEST_NOW.minusSeconds(120L)
                }

                then("the deferral warning reads the missing Retry-After as none") {
                    warnings.single() shouldStartWith "Slack rate limit (Retry-After=none);"
                }
            }

            `when`("the send fails transiently through every quick retry, or the dispatcher throws") {
                val transientRow = createOutboxRow(eventId = UUID.randomUUID().toString())
                val thrownRow = createOutboxRow(eventId = UUID.randomUUID().toString())
                val messageDispatcher = mockk<MessageDispatcher>()
                every { messageDispatcher.dispatch(event = any()) } returns
                    failOutput(event = payload(), reason = TRANSIENT_EXHAUSTED_REASON) andThenThrows
                    IllegalStateException("unexpected")
                val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher = messageDispatcher,
                        applicationEventPublisher = eventPublisher,
                    )

                then("both rows stay IN_PROGRESS for the sweep: no terminal write, no deferral, no event") {
                    shouldNotThrowAny {
                        service.dispatchClaimed(claim = OutboxClaim(row = transientRow, attempt = 1))
                        service.dispatchClaimed(claim = OutboxClaim(row = thrownRow, attempt = 1))
                    }
                    verify(exactly = 2) { outboxRepository.renewClaim(eventId = any(), attemptCount = 1, now = any()) }
                    verify(exactly = 0) {
                        outboxRepository.completeClaim(
                            eventId = any(),
                            attemptCount = any(),
                            status = any(),
                            now = any(),
                        )
                    }
                    verify(exactly = 0) {
                        outboxRepository.deferClaim(eventId = any(), attemptCount = any(), updatedAt = any())
                    }
                    verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
                }
            }

            `when`("the status write fails on every retry") {
                val row = createOutboxRow(eventId = UUID.randomUUID().toString())
                val eventPublisher = mockk<ApplicationEventPublisher>()
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                every {
                    outboxRepository.completeClaim(eventId = any(), attemptCount = any(), status = any(), now = any())
                } throws IllegalStateException("database unavailable")
                val service =
                    createRelayService(outboxRepository = outboxRepository, applicationEventPublisher = eventPublisher)

                then("after three attempts the failure is logged, not thrown, and the row is left IN_PROGRESS") {
                    shouldNotThrowAny { service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1)) }
                    verify(exactly = 3) {
                        outboxRepository.completeClaim(
                            eventId = any(),
                            attemptCount = any(),
                            status = any(),
                            now = any(),
                        )
                    }
                    verify(exactly = 0) { eventPublisher.publishEvent(ofType<OutboxUpdateEvent>()) }
                }
            }

            `when`("the status write throws an Error") {
                val row = createOutboxRow(eventId = UUID.randomUUID().toString())
                val thrown = StackOverflowError("deep")
                val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                every {
                    outboxRepository.completeClaim(eventId = any(), attemptCount = any(), status = any(), now = any())
                } throws thrown
                val service =
                    createRelayService(outboxRepository = outboxRepository, applicationEventPublisher = eventPublisher)

                then("the same Error reaches the dispatching thread after one attempt, and no outcome is published") {
                    shouldThrow<StackOverflowError> {
                        service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1))
                    } shouldBeSameInstanceAs thrown
                    verify(exactly = 1) {
                        outboxRepository.completeClaim(
                            eventId = any(),
                            attemptCount = any(),
                            status = any(),
                            now = any(),
                        )
                    }
                    verify(exactly = 0) { eventPublisher.publishEvent(ofType<OutboxUpdateEvent>()) }
                }
            }

            `when`("renewing the claim throws an Exception") {
                val row = createOutboxRow(eventId = UUID.randomUUID().toString())
                val messageDispatcher = mockk<MessageDispatcher>()
                val outboxRepository = mockk<MessageOutboxRepository>()
                every { outboxRepository.renewClaim(eventId = any(), attemptCount = any(), now = any()) } throws
                    DataAccessResourceFailureException("connection lost")
                val service =
                    createRelayService(outboxRepository = outboxRepository, messageDispatcher = messageDispatcher)

                then("it is logged and the row is left to the sweep without a send") {
                    shouldNotThrowAny { service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1)) }
                    verify(exactly = 0) { messageDispatcher.dispatch(event = any()) }
                }
            }

            `when`("renewing the claim throws an Error") {
                val row = createOutboxRow(eventId = UUID.randomUUID().toString())
                val thrown = StackOverflowError("deep")
                val messageDispatcher = mockk<MessageDispatcher>()
                val outboxRepository = mockk<MessageOutboxRepository>()
                every { outboxRepository.renewClaim(eventId = any(), attemptCount = any(), now = any()) } throws thrown
                val service =
                    createRelayService(outboxRepository = outboxRepository, messageDispatcher = messageDispatcher)

                then("the same Error propagates and nothing is sent") {
                    shouldThrow<StackOverflowError> {
                        service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1))
                    } shouldBeSameInstanceAs thrown
                    verify(exactly = 0) { messageDispatcher.dispatch(event = any()) }
                }
            }

            `when`("deferring a rate-limited row throws an Exception") {
                val eventId = UUID.randomUUID().toString()
                val row = createOutboxRow(eventId = eventId)
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                every { outboxRepository.deferClaim(eventId = any(), attemptCount = any(), updatedAt = any()) } throws
                    DataAccessResourceFailureException("connection lost")
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher =
                            dispatcherReturning(output = RateLimitedOutput(event = payload(), retryAfter = null)),
                    )

                then("the deferral was attempted, nothing escapes and no terminal status is written") {
                    shouldNotThrowAny { service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1)) }
                    verify(exactly = 1) {
                        outboxRepository.deferClaim(eventId = eventId, attemptCount = 1, updatedAt = any())
                    }
                    verify(exactly = 0) {
                        outboxRepository.completeClaim(
                            eventId = any(),
                            attemptCount = any(),
                            status = any(),
                            now = any(),
                        )
                    }
                }
            }

            `when`("deferring a rate-limited row throws an Error") {
                val row = createOutboxRow(eventId = UUID.randomUUID().toString())
                val thrown = StackOverflowError("deep")
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                every { outboxRepository.deferClaim(eventId = any(), attemptCount = any(), updatedAt = any()) } throws
                    thrown
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher =
                            dispatcherReturning(output = RateLimitedOutput(event = payload(), retryAfter = null)),
                    )

                then("the same Error propagates") {
                    shouldThrow<StackOverflowError> {
                        service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1))
                    } shouldBeSameInstanceAs thrown
                }
            }

            `when`("a claimed row is older than the give-up window") {
                val rowEventId = UUID.randomUUID()
                val row =
                    createOutboxRow(eventId = rowEventId.toString(), createdAt = DEFAULT_TEST_NOW.minusHours(25L))
                val payloadRenderer = mockk<OutboxPayloadRenderer>()
                val messageDispatcher = mockk<MessageDispatcher>()
                val published = slot<Any>()
                val eventPublisher = mockk<ApplicationEventPublisher>()
                every { eventPublisher.publishEvent(capture(published)) } returns Unit
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        payloadRenderer = payloadRenderer,
                        messageDispatcher = messageDispatcher,
                        applicationEventPublisher = eventPublisher,
                    )

                service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1))

                then("it is completed as FAILURE without renewing, rendering or sending") {
                    verify(exactly = 1) {
                        outboxRepository.completeClaim(
                            eventId = rowEventId.toString(),
                            attemptCount = 1,
                            status = MessageStatus.FAILURE.name,
                            now = DEFAULT_TEST_NOW,
                        )
                    }
                    verify(exactly = 0) {
                        outboxRepository.renewClaim(eventId = any(), attemptCount = any(), now = any())
                    }
                    verify(exactly = 0) { payloadRenderer.render(row = any()) }
                    verify(exactly = 0) { messageDispatcher.dispatch(event = any()) }
                    published.captured.shouldBeInstanceOf<MessagePublishFailedEvent>().eventId shouldBe rowEventId
                }
            }

            `when`("the row was written in a schema version this binary cannot read") {
                val row = createOutboxRow(eventId = UUID.randomUUID().toString(), schemaVersion = 9999)
                val payloadRenderer = mockk<OutboxPayloadRenderer>()
                val messageDispatcher = mockk<MessageDispatcher>()
                val eventPublisher = mockk<ApplicationEventPublisher>()
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        payloadRenderer = payloadRenderer,
                        messageDispatcher = messageDispatcher,
                        applicationEventPublisher = eventPublisher,
                    )

                then("it is left IN_PROGRESS for a binary that can read it: no renew, no send, no FAILURE") {
                    shouldNotThrowAny { service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1)) }
                    verify(exactly = 0) {
                        outboxRepository.renewClaim(eventId = any(), attemptCount = any(), now = any())
                    }
                    verify(exactly = 0) {
                        outboxRepository.completeClaim(
                            eventId = any(),
                            attemptCount = any(),
                            status = any(),
                            now = any(),
                        )
                    }
                    verify(exactly = 0) { payloadRenderer.render(row = any()) }
                    verify(exactly = 0) { messageDispatcher.dispatch(event = any()) }
                    verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
                }
            }

            `when`("the row carries a malformed eventId") {
                val row = createOutboxRow(eventId = "not-a-uuid")
                val payloadRenderer = mockk<OutboxPayloadRenderer>()
                val eventPublisher = mockk<ApplicationEventPublisher>()
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        payloadRenderer = payloadRenderer,
                        applicationEventPublisher = eventPublisher,
                    )

                service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 4))

                then("the claim is completed as FAILURE at once, without rendering, sending or publishing") {
                    verify(exactly = 1) {
                        outboxRepository.completeClaim(
                            eventId = "not-a-uuid",
                            attemptCount = 4,
                            status = MessageStatus.FAILURE.name,
                            now = DEFAULT_TEST_NOW,
                        )
                    }
                    verify(exactly = 0) {
                        outboxRepository.renewClaim(eventId = any(), attemptCount = any(), now = any())
                    }
                    verify(exactly = 0) { payloadRenderer.render(row = any()) }
                    verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
                }
            }
        }

        given("dispatchClaimed on a row that carries the rest of a chain") {
            val basicInfo = createCommandBasicInfo()
            val parts =
                (1..3).map { index ->
                    OutboundMessage.ChannelMessage(
                        target = ConversationTarget(id = basicInfo.channel),
                        content = MessageContent.Text(headline = "($index/3)", markdown = "part $index"),
                    )
                }
            val next = OutboundEnvelope(message = parts[1], basicInfo = basicInfo, continuation = listOf(parts[2]))

            class ChainLane(
                result: CommandOutput,
                completed: List<Int> = listOf(1),
                val transactionManager: PlatformTransactionManager = createStubTransactionManager(),
            ) {
                val eventId = UUID.randomUUID().toString()
                val row = createOutboxRow(eventId = eventId, schemaVersion = OutboxSchemaVersion.V3)
                val nextRow = createOutboxRow(eventId = UUID.randomUUID().toString())
                val outboxRepository =
                    mockk<MessageOutboxRepository>().also { repository ->
                        repository.stubClaimLifecycle()
                        every {
                            repository.completeClaim(eventId = any(), attemptCount = any(), status = any(), now = any())
                        } returnsMany completed
                        every { repository.save(nextRow) } returns nextRow
                    }
                val port =
                    mockk<OutboundMessagePort> {
                        every {
                            toRow(
                                message = parts[1],
                                basicInfo = basicInfo,
                                transport = Transport.SLACK,
                                continuation = listOf(parts[2]),
                            )
                        } returns nextRow
                    }
                val published = mutableListOf<Any>()
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        outboundMessagePort = port,
                        payloadRenderer =
                            mockk { every { render(row = row) } returns RenderedRow(payload = payload(), next = next) },
                        messageDispatcher = dispatcherReturning(output = result),
                        applicationEventPublisher = mockk { every { publishEvent(capture(published)) } returns Unit },
                        transactionManager = transactionManager,
                    )

                fun dispatch() = service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1))

                fun storedAs(status: MessageStatus, attemptCount: Int) {
                    every { outboxRepository.findById(eventId) } returns
                        Optional.of(createOutboxRow(eventId = eventId, status = status, attemptCount = attemptCount))
                }

                fun completed(status: MessageStatus) =
                    outboxRepository.completeClaim(
                        eventId = eventId,
                        attemptCount = 1,
                        status = status.name,
                        now = DEFAULT_TEST_NOW,
                    )
            }

            `when`("Slack accepts the part") {
                val lane = ChainLane(result = delivered())
                lane.dispatch()

                then("exactly one next row is staged with the following part, inside the SUCCESS write's transaction") {
                    verify(exactly = 1) { lane.outboxRepository.save(any()) }
                    verifyOrder {
                        lane.transactionManager.getTransaction(any())
                        lane.completed(status = MessageStatus.SUCCESS)
                        lane.outboxRepository.save(lane.nextRow)
                        lane.transactionManager.commit(any())
                    }
                }
            }

            `when`("Slack refuses the part for good") {
                val lane = ChainLane(result = failOutput(event = payload(), reason = "channel_not_found"))
                lane.dispatch()

                then("FAILURE is written and nothing more of the chain is staged") {
                    verify(exactly = 1) { lane.completed(status = MessageStatus.FAILURE) }
                    verify(exactly = 0) { lane.outboxRepository.save(any()) }
                }
            }

            `when`("the outcome of the part is unknown") {
                val lane =
                    ChainLane(result = failOutput(event = payload(), reason = "$OUTCOME_UNKNOWN_REASON: read timeout"))
                lane.dispatch()

                then("the row is not resent, and the chain goes on with the next part") {
                    verify(exactly = 1) { lane.completed(status = MessageStatus.FAILURE) }
                    verify(exactly = 1) { lane.outboxRepository.save(lane.nextRow) }
                }
            }

            `when`("Slack rate-limits the part") {
                val lane =
                    ChainLane(result = RateLimitedOutput(event = payload(), retryAfter = Duration.ofSeconds(30L)))
                lane.dispatch()

                then("the row is deferred with its continuation, and nothing is staged") {
                    verify(
                        exactly = 1,
                    ) { lane.outboxRepository.deferClaim(eventId = any(), attemptCount = 1, updatedAt = any()) }
                    verify(exactly = 0) { lane.outboxRepository.save(any()) }
                }
            }

            `when`("another owner already recorded the row") {
                val lane = ChainLane(result = delivered(), completed = listOf(0))
                lane.dispatch()

                then("this worker stages nothing, leaving the chain to the owner that recorded it") {
                    verify(exactly = 0) { lane.outboxRepository.save(any()) }
                }
            }

            `when`("the SUCCESS write commits but its acknowledgement is lost, so the write is retried") {
                val transactionManager =
                    createStubTransactionManager().also { manager ->
                        every { manager.commit(any()) } throws
                            TransactionSystemException("commit acknowledgement lost") andThen Unit
                    }
                val lane =
                    ChainLane(result = delivered(), completed = listOf(1, 0), transactionManager = transactionManager)
                lane.storedAs(status = MessageStatus.SUCCESS, attemptCount = 1)
                lane.dispatch()

                then("the retry finds the row already recorded and the next part is staged only once") {
                    verify(exactly = 2) { lane.completed(status = MessageStatus.SUCCESS) }
                    verify(exactly = 1) { lane.outboxRepository.save(any()) }
                }

                then("the re-read row counts as this owner's write, so the success event is still published") {
                    lane.published
                        .filterIsInstance<OutboxUpdateEvent>()
                        .single()
                        .shouldBeInstanceOf<MessagePublishSuccessEvent>()
                        .eventId shouldBe UUID.fromString(lane.eventId)
                }
            }

            `when`("the SUCCESS write fails at commit and a newer claim owns the row when it is retried") {
                val transactionManager =
                    createStubTransactionManager().also { manager ->
                        every { manager.commit(any()) } throws
                            TransactionSystemException("commit failed") andThen Unit
                    }
                val lane =
                    ChainLane(result = delivered(), completed = listOf(1, 0), transactionManager = transactionManager)
                lane.storedAs(status = MessageStatus.IN_PROGRESS, attemptCount = 2)
                lane.dispatch()

                then("the row is not this owner's, so no outcome event is published") {
                    lane.published.filterIsInstance<OutboxUpdateEvent>() shouldBe emptyList()
                }
            }

            listOf(
                "a newer claim already recorded SUCCESS" to (MessageStatus.SUCCESS to 2),
                "the same claim's row holds another status" to (MessageStatus.FAILURE to 1),
            ).forEach { (what, stored) ->
                `when`("the SUCCESS write fails at commit and, on the retry, $what") {
                    val transactionManager =
                        createStubTransactionManager().also { manager ->
                            every { manager.commit(any()) } throws
                                TransactionSystemException("commit failed") andThen Unit
                        }
                    val lane =
                        ChainLane(
                            result = delivered(),
                            completed = listOf(1, 0),
                            transactionManager = transactionManager,
                        )
                    lane.storedAs(status = stored.first, attemptCount = stored.second)
                    lane.dispatch()

                    then("both the status and the attempt must match, so no outcome event is published") {
                        lane.published.filterIsInstance<OutboxUpdateEvent>() shouldBe emptyList()
                    }
                }
            }
        }

        given("dispatchClaimed on a slash reply that carries a fallback") {
            val basicInfo = createCommandBasicInfo()
            val directReply =
                OutboundEnvelope(
                    message =
                        OutboundMessage.ChannelMessage(
                            target = ConversationTarget(id = basicInfo.publisherId),
                            content = MessageContent.Text(headline = null, markdown = "copy"),
                        ),
                    basicInfo = basicInfo.copy(channel = basicInfo.publisherId),
                )

            val loggerName = SlackMessageRelayServiceImpl::class.java.packageName

            class FallbackLane(
                result: CommandOutput,
                trigger: FallbackTrigger = FallbackTrigger.CHANNEL_REFUSED,
                completed: List<Int> = listOf(1),
                next: OutboundEnvelope? = null,
                val transactionManager: PlatformTransactionManager = createStubTransactionManager(),
            ) {
                val eventId = UUID.randomUUID().toString()
                val row = createOutboxRow(eventId = eventId, schemaVersion = OutboxSchemaVersion.V6)
                val fallbackRow = createOutboxRow(eventId = UUID.randomUUID().toString())
                val published = mutableListOf<Any>()
                val outboxRepository =
                    mockk<MessageOutboxRepository>().also { repository ->
                        repository.stubClaimLifecycle()
                        every {
                            repository.completeClaim(eventId = any(), attemptCount = any(), status = any(), now = any())
                        } returnsMany completed
                        every { repository.save(fallbackRow) } returns fallbackRow
                    }
                val port =
                    mockk<OutboundMessagePort> {
                        every {
                            toRow(
                                message = directReply.message,
                                basicInfo = directReply.basicInfo,
                                transport = Transport.SLACK,
                                continuation = emptyList(),
                            )
                        } returns fallbackRow
                    }
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        outboundMessagePort = port,
                        payloadRenderer =
                            mockk {
                                every { render(row = row) } returns
                                    RenderedRow(
                                        payload = payload(),
                                        next = next,
                                        fallback = FallbackPlan(envelope = directReply, trigger = trigger),
                                    )
                            },
                        messageDispatcher = dispatcherReturning(output = result),
                        applicationEventPublisher =
                            mockk { every { publishEvent(capture(published)) } returns Unit },
                        transactionManager = transactionManager,
                    )

                fun dispatch() = service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1))

                fun completed(status: MessageStatus) =
                    outboxRepository.completeClaim(
                        eventId = eventId,
                        attemptCount = 1,
                        status = status.name,
                        now = DEFAULT_TEST_NOW,
                    )
            }

            listOf(
                "not_in_channel",
                "no_permission",
                "channel_not_found",
                "restricted_action_read_only_channel",
                "user_not_in_channel",
            ).forEach { code ->
                `when`("Slack refuses the reply's channel with $code") {
                    val lane = FallbackLane(result = failOutput(event = payload(), reason = code))
                    val warnings =
                        captureLogs(loggerName = loggerName) { lane.dispatch() }.messagesAt(level = Level.WARN)

                    then("FAILURE is written and the direct reply is staged once, inside the same transaction") {
                        verify(exactly = 1) { lane.outboxRepository.save(any()) }
                        verifyOrder {
                            lane.transactionManager.getTransaction(any())
                            lane.completed(status = MessageStatus.FAILURE)
                            lane.outboxRepository.save(lane.fallbackRow)
                            lane.transactionManager.commit(any())
                        }
                    }

                    then("no delivery is announced, only the failure, and the warning names the refusal") {
                        lane.published.map { it::class } shouldBe listOf(MessagePublishFailedEvent::class)
                        warnings shouldBe
                            listOf(
                                "Slack refused the channel of eventId=${lane.eventId} " +
                                    "idempotencyKey=${lane.row.idempotencyKey} ($code); " +
                                    "staged its direct reply to ${basicInfo.publisherId}",
                            )
                    }
                }
            }

            `when`("Slack rejects the reply for a reason that is not a channel refusal") {
                val lane = FallbackLane(result = failOutput(event = payload(), reason = "invalid_blocks"))
                lane.dispatch()

                then("FAILURE is written and no direct reply is staged") {
                    verify(exactly = 1) { lane.completed(status = MessageStatus.FAILURE) }
                    verify(exactly = 0) { lane.outboxRepository.save(any()) }
                }
            }

            `when`("a replace-original fallback meets the same rejection") {
                val lane =
                    FallbackLane(
                        result = failOutput(event = payload(), reason = "invalid_blocks"),
                        trigger = FallbackTrigger.ANY_DEFINITIVE_FAILURE,
                    )
                val warnings = captureLogs(loggerName = loggerName) { lane.dispatch() }.messagesAt(level = Level.WARN)

                then("its fallback is still staged for any definitive failure, with the replace wording") {
                    verify(exactly = 1) { lane.completed(status = MessageStatus.FAILURE) }
                    verify(exactly = 1) { lane.outboxRepository.save(lane.fallbackRow) }
                    warnings shouldBe
                        listOf(
                            "Replacing the original message failed for eventId=${lane.eventId} " +
                                "idempotencyKey=${lane.row.idempotencyKey} (invalid_blocks); staged its fallback",
                        )
                }
            }

            `when`("another owner already recorded the refused row") {
                val refused = failOutput(event = payload(), reason = "not_in_channel")
                val lane = FallbackLane(result = refused, completed = listOf(0))
                val warnings = captureLogs(loggerName = loggerName) { lane.dispatch() }.messagesAt(level = Level.WARN)

                then("this worker stages no direct reply and claims none: the only warning is the take-over") {
                    verify(exactly = 1) { lane.completed(status = MessageStatus.FAILURE) }
                    verify(exactly = 0) { lane.outboxRepository.save(any()) }
                    warnings shouldBe
                        listOf("Claim attempt=1 on eventId=${lane.eventId} was taken over; FAILURE not recorded")
                }
            }

            `when`("every status write of the refused row fails") {
                val lane = FallbackLane(result = failOutput(event = payload(), reason = "not_in_channel"))
                every {
                    lane.outboxRepository.completeClaim(
                        eventId = any(),
                        attemptCount = any(),
                        status = any(),
                        now = any(),
                    )
                } throws DataAccessResourceFailureException("database down")
                val warnings = captureLogs(loggerName = loggerName) { lane.dispatch() }.messagesAt(level = Level.WARN)

                then("nothing is staged and no warning claims a direct reply") {
                    verify(exactly = STATUS_WRITE_ATTEMPTS.toInt()) { lane.completed(status = MessageStatus.FAILURE) }
                    verify(exactly = 0) { lane.outboxRepository.save(any()) }
                    warnings shouldBe emptyList()
                }
            }

            `when`("the send is rate-limited, held as access-blocked, or has an unknown outcome") {
                val lanes =
                    listOf(
                        RateLimitedOutput(event = payload(), retryAfter = Duration.ofSeconds(30L)),
                        failOutput(event = payload(), reason = ACCESS_BLOCKED_REASON),
                        failOutput(event = payload(), reason = "$OUTCOME_UNKNOWN_REASON: timeout"),
                    ).map { result -> FallbackLane(result = result) }
                lanes.forEach { lane -> lane.dispatch() }

                then("none of them stages the direct reply") {
                    lanes.forEach { lane -> verify(exactly = 0) { lane.outboxRepository.save(any()) } }
                }
            }

            `when`("a refused row also carried the rest of a chain") {
                val chainPart =
                    OutboundMessage.ChannelMessage(
                        target = ConversationTarget(id = basicInfo.channel),
                        content = MessageContent.Text(headline = "(2/2)", markdown = "part 2"),
                    )
                val next = OutboundEnvelope(message = chainPart, basicInfo = basicInfo)
                val lane = FallbackLane(result = failOutput(event = payload(), reason = "not_in_channel"), next = next)
                every { lane.row.payload } returns
                    OutboundMessageCodec.encode(
                        envelope =
                            OutboundEnvelope(
                                message = chainPart,
                                basicInfo = basicInfo,
                                continuation = listOf(chainPart),
                            ),
                    )
                val errors = captureLogs(loggerName = loggerName) { lane.dispatch() }.messagesAt(level = Level.ERROR)

                then("the direct reply is staged instead of the next part, and the dropped part is logged") {
                    verify(exactly = 1) { lane.outboxRepository.save(lane.fallbackRow) }
                    errors shouldBe
                        listOf(
                            "Dropping 1 chained parts after eventId=${lane.eventId} " +
                                "idempotencyKey=${lane.row.idempotencyKey} ended FAILURE",
                        )
                }
            }
        }

        given("saveOutboxMessage for messages staged in order") {
            `when`("the enqueued event carries a continuation") {
                val basicInfo = createCommandBasicInfo()
                val parts =
                    (1..2).map { index ->
                        OutboundMessage.ChannelMessage(
                            target = ConversationTarget(id = basicInfo.channel),
                            content = MessageContent.Text(headline = null, markdown = "part $index"),
                        )
                    }
                val row = createOutboxRow(eventId = UUID.randomUUID().toString())
                val port = mockk<OutboundMessagePort>()
                every {
                    port.toRow(message = parts[0], basicInfo = basicInfo, continuation = listOf(parts[1]))
                } returns row
                val outboxRepository = mockk<MessageOutboxRepository>()
                every { outboxRepository.save(row) } returns row
                val service = createRelayService(outboxRepository = outboxRepository, outboundMessagePort = port)

                service.saveOutboxMessage(
                    event =
                        OutboundMessageEnqueued(
                            idempotencyKey = basicInfo.idempotencyKey,
                            payload =
                                OutboundMessageEnqueuedPayload(
                                    message = parts[0],
                                    basicInfo = basicInfo,
                                    continuation = listOf(parts[1]),
                                ),
                        ),
                )

                then("one row is saved, carrying the rest behind the first part") {
                    verify(exactly = 1) { outboxRepository.save(row) }
                }
            }
        }

        given("batchPendingMessages") {
            `when`("several claims are handed over") {
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                val service = createRelayService(outboxRepository = outboxRepository)
                val eventIds = List(size = 2) { UUID.randomUUID().toString() }
                val claims = eventIds.map { OutboxClaim(row = createOutboxRow(eventId = it), attempt = 2) }

                service.batchPendingMessages(claims = claims)

                then("each claim runs on the executor with its own attempt") {
                    eventIds.forEach { eventId ->
                        verify(exactly = 1) {
                            outboxRepository.renewClaim(eventId = eventId, attemptCount = 2, now = any())
                        }
                    }
                }
            }

            `when`("claims are handed over within the slots reserved for them on a bounded pool") {
                val release = CountDownLatch(1)
                val firstStarted = CountDownLatch(1)
                val messageDispatcher = mockk<MessageDispatcher>()
                every { messageDispatcher.dispatch(event = any()) } answers {
                    firstStarted.countDown()
                    release.await(5L, TimeUnit.SECONDS)
                    delivered()
                }
                val pool = boundedPool(threads = 1, queue = 1)
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher = messageDispatcher,
                        relayTaskExecutor = pool,
                        appConfig = appConfigWithBatchSize(batchSize = 1),
                    )

                val firstReserved = service.reserveDispatchSlots(wanted = 5)
                service.batchPendingMessages(claims = listOf(newClaim()))
                firstStarted.await(5L, TimeUnit.SECONDS)
                val reservedWhileRunning = service.reserveDispatchSlots(wanted = 5)
                service.batchPendingMessages(claims = listOf(newClaim()))
                val reservedWhileQueued = service.reserveDispatchSlots(wanted = 5)
                release.countDown()
                pool.threadPoolExecutor.shutdown()
                pool.threadPoolExecutor.awaitTermination(5L, TimeUnit.SECONDS)

                then("a claim holds its slot only until a pool thread starts it, so the queue never overflows") {
                    firstReserved shouldBe 1
                    reservedWhileRunning shouldBe 1
                    reservedWhileQueued shouldBe 0
                    pool.threadPoolExecutor.completedTaskCount shouldBe 2L
                    service.reserveDispatchSlots(wanted = 5) shouldBe 1
                }
            }

            `when`("more claims arrive than the bounded pool can take") {
                val release = CountDownLatch(1)
                val firstStarted = CountDownLatch(1)
                val dispatchThreads = ConcurrentLinkedQueue<String>()
                val messageDispatcher = mockk<MessageDispatcher>()
                every { messageDispatcher.dispatch(event = any()) } answers {
                    dispatchThreads.add(Thread.currentThread().name)
                    firstStarted.countDown()
                    release.await(5L, TimeUnit.SECONDS)
                    delivered()
                }
                val pool = boundedPool(threads = 1, queue = 1)
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher = messageDispatcher,
                        relayTaskExecutor = pool,
                        appConfig = appConfigWithBatchSize(batchSize = 3),
                    )
                val eventIds = List(size = 3) { UUID.randomUUID().toString() }
                val claims = eventIds.map { OutboxClaim(row = createOutboxRow(eventId = it), attempt = 1) }
                val reserved = service.reserveDispatchSlots(wanted = 3)

                shouldNotThrowAny { service.batchPendingMessages(claims = claims) }
                firstStarted.await(5L, TimeUnit.SECONDS)
                release.countDown()
                pool.threadPoolExecutor.shutdown()
                pool.threadPoolExecutor.awaitTermination(5L, TimeUnit.SECONDS)

                then("the rejected claim is left for the recovery sweep, never run on the caller's thread") {
                    reserved shouldBe 3
                    dispatchThreads.toList() shouldHaveSize 2
                    dispatchThreads.forEach { it.startsWith("relay-test-") shouldBe true }
                    verify(exactly = 0) {
                        outboxRepository.renewClaim(eventId = eventIds.last(), attemptCount = any(), now = any())
                    }
                    service.reserveDispatchSlots(wanted = 10) shouldBe 3
                }
            }

            `when`("the production pool's threads already exist and sit idle between full reservations") {
                val pool = AsyncConfig().relayTaskExecutor(appConfig = AppConfig()) as ThreadPoolTaskExecutor
                pool.threadPoolExecutor.prestartAllCoreThreads()
                val dispatched = AtomicInteger(0)
                val messageDispatcher = mockk<MessageDispatcher>()
                every { messageDispatcher.dispatch(event = any()) } answers {
                    dispatched.incrementAndGet()
                    delivered()
                }
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher = messageDispatcher,
                        relayTaskExecutor = pool,
                    )
                val claims = List(size = pool.queueCapacity) { newClaim() }
                val rounds = 20
                val reservations = mutableListOf<Int>()
                repeat(rounds) {
                    val before = dispatched.get()
                    val reserved = service.reserveDispatchSlots(wanted = Int.MAX_VALUE)
                    reservations.add(reserved)
                    service.batchPendingMessages(claims = claims.take(reserved))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L)
                    while (dispatched.get() < before + reserved && System.nanoTime() < deadline) {
                        Thread.sleep(1L)
                    }
                }
                pool.shutdown()

                then("overflow is rejected, a full reservation is the queue capacity, and every claim is dispatched") {
                    pool.threadPoolExecutor.rejectedExecutionHandler
                        .shouldBeInstanceOf<ThreadPoolExecutor.AbortPolicy>()
                    reservations.toSet() shouldBe setOf(pool.queueCapacity)
                    dispatched.get() shouldBe rounds * pool.queueCapacity
                }
            }

            `when`("a claim on the production pool throws an Error") {
                val pool = AsyncConfig().relayTaskExecutor(appConfig = AppConfig()) as ThreadPoolTaskExecutor
                val failingId = UUID.randomUUID().toString()
                val failing = OutboxClaim(row = createOutboxRow(eventId = failingId), attempt = 1)
                val next = newClaim()
                val nextDispatched = CountDownLatch(1)
                val messageDispatcher = mockk<MessageDispatcher>()
                every { messageDispatcher.dispatch(event = any()) } answers
                    { delivered().also { nextDispatched.countDown() } }
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                every {
                    outboxRepository.renewClaim(eventId = failingId, attemptCount = any(), now = any())
                } throws StackOverflowError("deep")
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher = messageDispatcher,
                        relayTaskExecutor = pool,
                    )
                service.batchPendingMessages(claims = listOf(failing))
                service.batchPendingMessages(claims = listOf(next))
                val nextRan = nextDispatched.await(10L, TimeUnit.SECONDS)
                val workerHandler =
                    pool.threadPoolExecutor.threadFactory
                        .newThread {}
                        .uncaughtExceptionHandler
                pool.shutdown()
                val logged =
                    captureLogs(loggerName = AsyncConfig::class.java.packageName) {
                        RELAY_UNCAUGHT_ERROR_LOGGER.uncaughtException(
                            Thread(null, {}, "relay-9"),
                            StackOverflowError("deep"),
                        )
                    }.messagesAt(level = Level.ERROR)

                then(
                    "the pool's threads log an escaping Error with the thread name and the pool goes on with the next claim",
                ) {
                    nextRan shouldBe true
                    workerHandler shouldBeSameInstanceAs RELAY_UNCAUGHT_ERROR_LOGGER
                    logged.single { it.startsWith("Relay worker relay-9 ") } shouldStartWith
                        "Relay worker relay-9 ended on an uncaught java.lang.StackOverflowError"
                }
            }

            `when`("the executor is not a bounded pool") {
                val service =
                    createRelayService(outboxRepository = mockk(), appConfig = appConfigWithBatchSize(batchSize = 3))

                then("the slots are still the configured queue capacity") {
                    service.reserveDispatchSlots(wanted = 10) shouldBe 3
                    service.reserveDispatchSlots(wanted = 1) shouldBe 0
                    service.releaseDispatchSlots(count = 2)
                    service.reserveDispatchSlots(wanted = 10) shouldBe 2
                    service.releaseDispatchSlots(count = 100)
                    service.reserveDispatchSlots(wanted = Int.MAX_VALUE) shouldBe 3
                }
            }
        }

        given("stop() at the start of the context close, with one dispatch running and claims queued") {
            val release = CountDownLatch(1)
            val firstStarted = CountDownLatch(1)
            val messageDispatcher = mockk<MessageDispatcher>()
            every { messageDispatcher.dispatch(event = any()) } answers {
                firstStarted.countDown()
                release.await(5L, TimeUnit.SECONDS)
                delivered()
            }
            val pool = boundedPool(threads = 1, queue = 3)
            val outboxRepository = mockk<MessageOutboxRepository>()
            outboxRepository.stubClaimLifecycle()
            val service =
                createRelayService(
                    outboxRepository = outboxRepository,
                    messageDispatcher = messageDispatcher,
                    relayTaskExecutor = pool,
                    appConfig = appConfigWithBatchSize(batchSize = 3),
                )
            val eventIds = List(size = 5) { UUID.randomUUID().toString() }
            val claims = eventIds.map { OutboxClaim(row = createOutboxRow(eventId = it), attempt = 1) }
            service.batchPendingMessages(claims = claims.take(service.reserveDispatchSlots(wanted = 3)))
            firstStarted.await(5L, TimeUnit.SECONDS)

            `when`("the context begins to close") {
                service.stop()
                val reservedAfterStop = service.reserveDispatchSlots(wanted = 3)
                service.dispatchClaimed(claim = claims[3])
                service.batchPendingMessages(claims = listOf(claims[4]))
                release.countDown()
                pool.threadPoolExecutor.shutdown()
                pool.threadPoolExecutor.awaitTermination(5L, TimeUnit.SECONDS)

                then("only the running dispatch finishes; queued and later claims stay IN_PROGRESS unsent") {
                    service.isRunning shouldBe false
                    reservedAfterStop shouldBe 0
                    pool.threadPoolExecutor.completedTaskCount shouldBe 1L
                    verify(exactly = 1) { messageDispatcher.dispatch(event = any()) }
                    verify(exactly = 1) {
                        outboxRepository.completeClaim(
                            eventId = eventIds[0],
                            attemptCount = 1,
                            status = MessageStatus.SUCCESS.name,
                            now = any(),
                        )
                    }
                    eventIds.drop(1).forEach { eventId ->
                        verify(exactly = 0) {
                            outboxRepository.renewClaim(eventId = eventId, attemptCount = any(), now = any())
                        }
                    }
                }
            }
        }

        given("reserveDispatchSlots called from several threads at once") {
            val service = createRelayService(outboxRepository = mockk())
            val start = CountDownLatch(1)
            val results = ConcurrentLinkedQueue<Int>()
            val reservers =
                List(size = 8) {
                    thread {
                        start.await(5L, TimeUnit.SECONDS)
                        results.add(service.reserveDispatchSlots(wanted = 30))
                    }
                }

            `when`("each asks for more than its share of the relay") {
                start.countDown()
                reservers.forEach { it.join(5_000L) }

                then("together they get exactly the capacity, never a slot twice") {
                    results.sum() shouldBe AppConfig().outbox.polling.batchSize
                    results.forEach { it shouldBeLessThanOrEqual 30 }
                }
            }
        }
    })
