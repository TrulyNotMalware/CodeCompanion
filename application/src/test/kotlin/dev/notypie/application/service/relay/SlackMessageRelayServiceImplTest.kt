package dev.notypie.application.service.relay

import dev.notypie.application.outbox.DEFAULT_TEST_NOW
import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.application.outbox.createRelayService
import dev.notypie.application.outbox.stubClaimLifecycle
import dev.notypie.domain.command.createCommandBasicInfo
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.outbound.ConversationTarget
import dev.notypie.domain.command.outbound.MessageContent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.impl.command.ACCESS_BLOCKED_DEFER
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
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.OutboundMessagePort
import dev.notypie.repository.outbox.dto.MessagePublishFailedEvent
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

class SlackMessageRelayServiceImplTest :
    BehaviorSpec({
        fun payload() = createPostEventPayloadContents(commandDetailType = CommandDetailType.SIMPLE_TEXT)

        fun delivered() = successOutput(payload = payload(), commandType = CommandType.EXTERNAL_API)

        fun dispatcherReturning(output: CommandOutput): MessageDispatcher =
            mockk { every { dispatch(event = any()) } returns output }

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

        given("dispatchClaimed (deliver-time render + dispatch)") {
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
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        payloadRenderer = payloadRenderer,
                        messageDispatcher = messageDispatcher,
                        applicationEventPublisher = eventPublisher,
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

                then("the success event keys on the ROW eventId, not any renderer-minted id") {
                    published.captured shouldBe
                        MessagePublishSuccessEvent(eventId = rowEventId, messageTs = "1700000000.000200")
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
                    verify(exactly = 0) { eventPublisher.publishEvent(any()) }
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
                    verify(exactly = 0) { eventPublisher.publishEvent(any()) }
                }
            }

            `when`("a listener of the outcome event throws after SUCCESS was recorded") {
                val eventId = UUID.randomUUID().toString()
                val row = createOutboxRow(eventId = eventId)
                val eventPublisher = mockk<ApplicationEventPublisher>()
                every { eventPublisher.publishEvent(any()) } throws IllegalStateException("standup ts write failed")
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
                    verify(exactly = 0) { eventPublisher.publishEvent(any()) }
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

                service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1))

                then("it waits the default minute plus its spread") {
                    deferredTo.captured shouldBeGreaterThanOrEqualTo DEFAULT_TEST_NOW.minusSeconds(240L)
                    deferredTo.captured shouldBeLessThan DEFAULT_TEST_NOW.minusSeconds(120L)
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
                    verify(exactly = 0) { eventPublisher.publishEvent(any()) }
                }
            }

            `when`("Slack refuses the bot token or workspace") {
                val row = createOutboxRow(eventId = UUID.randomUUID().toString())
                val deferredTo = slot<LocalDateTime>()
                val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                every {
                    outboxRepository.deferClaim(eventId = any(), attemptCount = 1, updatedAt = capture(deferredTo))
                } returns 1
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher =
                            dispatcherReturning(
                                output = failOutput(event = payload(), reason = ACCESS_BLOCKED_REASON),
                            ),
                        applicationEventPublisher = eventPublisher,
                    )

                service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1))

                then("the row is held for the longer access wait instead of failing, and nothing terminal happens") {
                    val eligibleAt = deferredTo.captured.plusSeconds(300L)
                    eligibleAt shouldBeGreaterThanOrEqualTo DEFAULT_TEST_NOW.plus(ACCESS_BLOCKED_DEFER)
                    eligibleAt shouldBeLessThan DEFAULT_TEST_NOW.plus(ACCESS_BLOCKED_DEFER).plusMinutes(2L)
                    verify(exactly = 0) {
                        outboxRepository.completeClaim(
                            eventId = any(),
                            attemptCount = any(),
                            status = any(),
                            now = any(),
                        )
                    }
                    verify(exactly = 0) { eventPublisher.publishEvent(any()) }
                }
            }

            `when`("the dispatcher cannot tell whether Slack acted on a non-idempotent send") {
                val rowEventId = UUID.randomUUID()
                val row = createOutboxRow(eventId = rowEventId.toString())
                val published = slot<Any>()
                val eventPublisher = mockk<ApplicationEventPublisher>()
                every { eventPublisher.publishEvent(capture(published)) } returns Unit
                val outboxRepository = mockk<MessageOutboxRepository>()
                outboxRepository.stubClaimLifecycle()
                val service =
                    createRelayService(
                        outboxRepository = outboxRepository,
                        messageDispatcher =
                            dispatcherReturning(
                                output = failOutput(event = payload(), reason = OUTCOME_UNKNOWN_REASON),
                            ),
                        applicationEventPublisher = eventPublisher,
                    )

                service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1))

                then("the row is closed as FAILURE at once, so the recovery sweep never resends it") {
                    verify(exactly = 1) {
                        outboxRepository.completeClaim(
                            eventId = rowEventId.toString(),
                            attemptCount = 1,
                            status = MessageStatus.FAILURE.name,
                            now = DEFAULT_TEST_NOW,
                        )
                    }
                    verify(exactly = 0) {
                        outboxRepository.deferClaim(eventId = any(), attemptCount = any(), updatedAt = any())
                    }
                    published.captured.shouldBeInstanceOf<MessagePublishFailedEvent>().reason shouldBe
                        OUTCOME_UNKNOWN_REASON
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

                then("the failure is logged, not thrown, and the row is left IN_PROGRESS for the sweep") {
                    shouldNotThrowAny { service.dispatchClaimed(claim = OutboxClaim(row = row, attempt = 1)) }
                    verify(exactly = 5) {
                        outboxRepository.completeClaim(
                            eventId = any(),
                            attemptCount = any(),
                            status = any(),
                            now = any(),
                        )
                    }
                    verify(exactly = 0) { eventPublisher.publishEvent(any()) }
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
                    verify(exactly = 0) { eventPublisher.publishEvent(any()) }
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
        }
    })
