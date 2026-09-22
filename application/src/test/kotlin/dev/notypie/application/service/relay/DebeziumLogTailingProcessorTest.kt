package dev.notypie.application.service.relay

import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.impl.command.RATE_LIMITED_REASON
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.dto.MessagePublishFailedEvent
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import java.util.Optional
import java.util.UUID

class DebeziumLogTailingProcessorTest :
    BehaviorSpec({
        data class Fixture(
            val dispatcher: MessageDispatcher,
            val renderer: OutboxPayloadRenderer,
            val eventPublisher: ApplicationEventPublisher,
            val outboxRepository: MessageOutboxRepository,
            val processor: DebeziumLogTailingProcessor,
        )

        fun fixture(dispatchOk: Boolean = true, errorReason: String = "slack rejected"): Fixture {
            val dispatcher = mockk<MessageDispatcher>()
            val renderer = mockk<OutboxPayloadRenderer>(relaxed = true)
            val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
            val outboxRepository = mockk<MessageOutboxRepository>()
            every { dispatcher.dispatch(event = any()) } returns
                mockk(relaxed = true) {
                    every { ok } returns dispatchOk
                    every { this@mockk.errorReason } returns if (dispatchOk) "" else errorReason
                }
            return Fixture(
                dispatcher = dispatcher,
                renderer = renderer,
                eventPublisher = eventPublisher,
                outboxRepository = outboxRepository,
                processor =
                    DebeziumLogTailingProcessor(
                        messageDispatcher = dispatcher,
                        payloadRenderer = renderer,
                        eventPublisher = eventPublisher,
                        outboxRepository = outboxRepository,
                    ),
            )
        }

        fun MessageOutboxRepository.currentRowIs(eventId: String, status: MessageStatus) {
            every { findById(eventId) } returns Optional.of(createOutboxRow(eventId = eventId, status = status))
        }

        given("a PENDING insert whose row is still PENDING in the database") {
            val eventId = UUID.randomUUID().toString()
            val f = fixture()
            f.outboxRepository.currentRowIs(eventId = eventId, status = MessageStatus.PENDING)
            every { f.outboxRepository.claimPending(eventIds = listOf(eventId)) } returns 1

            `when`("the record is consumed") {
                f.processor.consume(envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = eventId)))

                then("the row is claimed, dispatched once, and a SUCCESS update is published") {
                    verify(exactly = 1) { f.outboxRepository.claimPending(eventIds = listOf(eventId)) }
                    verify(exactly = 1) { f.dispatcher.dispatch(event = any()) }
                    val published = slot<Any>()
                    verify(exactly = 1) { f.eventPublisher.publishEvent(capture(published)) }
                    published.captured.shouldBeInstanceOf<MessagePublishSuccessEvent>().eventId shouldBe
                        UUID.fromString(eventId)
                }
            }
        }

        given("a redelivered record whose row already reached SUCCESS") {
            val eventId = UUID.randomUUID().toString()
            val f = fixture()
            f.outboxRepository.currentRowIs(eventId = eventId, status = MessageStatus.SUCCESS)

            `when`("the record is consumed again after a crash between the status update and the offset commit") {
                f.processor.consume(envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = eventId)))

                then("nothing is sent a second time") {
                    verify(exactly = 0) { f.outboxRepository.claimPending(eventIds = any()) }
                    verify(exactly = 0) { f.dispatcher.dispatch(event = any()) }
                    verify(exactly = 0) { f.eventPublisher.publishEvent(any()) }
                }
            }
        }

        given("a record whose claim is lost to another consumer") {
            val eventId = UUID.randomUUID().toString()
            val f = fixture()
            f.outboxRepository.currentRowIs(eventId = eventId, status = MessageStatus.PENDING)
            every { f.outboxRepository.claimPending(eventIds = listOf(eventId)) } returns 0

            `when`("the record is consumed") {
                f.processor.consume(envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = eventId)))

                then("it is not dispatched") {
                    verify(exactly = 0) { f.dispatcher.dispatch(event = any()) }
                }
            }
        }

        given("a record left IN_PROGRESS by an interrupted consumer") {
            val eventId = UUID.randomUUID().toString()
            val f = fixture()
            f.outboxRepository.currentRowIs(eventId = eventId, status = MessageStatus.IN_PROGRESS)

            `when`("the record is redelivered") {
                f.processor.consume(envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = eventId)))

                then("it is dispatched again rather than stranded") {
                    verify(exactly = 0) { f.outboxRepository.claimPending(eventIds = any()) }
                    verify(exactly = 1) { f.dispatcher.dispatch(event = any()) }
                }
            }
        }

        given("a dispatch that fails") {
            val eventId = UUID.randomUUID().toString()
            val f = fixture(dispatchOk = false)
            f.outboxRepository.currentRowIs(eventId = eventId, status = MessageStatus.PENDING)
            every { f.outboxRepository.claimPending(eventIds = listOf(eventId)) } returns 1

            `when`("the record is consumed") {
                f.processor.consume(envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = eventId)))

                then("a FAILURE update is published and the listener returns normally") {
                    val published = slot<Any>()
                    verify(exactly = 1) { f.eventPublisher.publishEvent(capture(published)) }
                    published.captured.shouldBeInstanceOf<MessagePublishFailedEvent>().reason shouldBe "slack rejected"
                }
            }
        }

        given("a dispatch that hits Slack's rate limit") {
            val eventId = UUID.randomUUID().toString()
            val f = fixture(dispatchOk = false, errorReason = RATE_LIMITED_REASON)
            f.outboxRepository.currentRowIs(eventId = eventId, status = MessageStatus.PENDING)
            every { f.outboxRepository.claimPending(eventIds = listOf(eventId)) } returns 1

            `when`("the record is consumed") {
                f.processor.consume(envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = eventId)))

                then("no status update is published: the row stays IN_PROGRESS for the recovery sweep") {
                    verify(exactly = 0) { f.eventPublisher.publishEvent(any()) }
                }
            }
        }

        given("CDC events this processor must ignore") {
            val f = fixture()

            `when`("the record is a tombstone") {
                f.processor.consume(envelope = null)

                then("it is skipped without touching the database") {
                    verify(exactly = 0) { f.outboxRepository.findById(any()) }
                }
            }

            `when`("the record is a delete event with no after-image") {
                f.processor.consume(
                    envelope = createCdcEnvelope(after = null, before = createOutboxAfterImage(), op = "d"),
                )

                then("it is skipped") {
                    verify(exactly = 0) { f.outboxRepository.findById(any()) }
                }
            }

            `when`("the after-image is the IN_PROGRESS update this processor caused") {
                f.processor.consume(
                    envelope =
                        createCdcEnvelope(
                            after = createOutboxAfterImage(status = MessageStatus.IN_PROGRESS),
                            op = "u",
                        ),
                )

                then("it is skipped") {
                    verify(exactly = 0) { f.outboxRepository.findById(any()) }
                }
            }
        }

        given("records that can never be processed") {
            val f = fixture()

            `when`("the after-image cannot be mapped to an outbox row") {
                then("a CdcRecordParseException reaches the error handler instead of a silent skip") {
                    shouldThrow<CdcRecordParseException> {
                        f.processor.consume(envelope = createCdcEnvelope(after = mapOf("garbage" to 1)))
                    }
                }
            }

            `when`("the event id is not a UUID") {
                then("a CdcRecordParseException reaches the error handler") {
                    shouldThrow<CdcRecordParseException> {
                        f.processor.consume(
                            envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = "not-a-uuid")),
                        )
                    }
                    verify(exactly = 0) { f.outboxRepository.findById(any()) }
                }
            }
        }
    })
