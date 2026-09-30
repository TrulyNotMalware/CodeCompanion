package dev.notypie.application.service.relay

import dev.notypie.application.configurations.CdcDeadLetterRecovery
import dev.notypie.application.configurations.DEAD_LETTER_MAX_BLOCK
import dev.notypie.application.configurations.METRIC_DLT_PUBLISH_FAILURES
import dev.notypie.application.configurations.cdcDeadLetterRecoverer
import dev.notypie.application.configurations.deadLetterProducerFactory
import dev.notypie.application.outbox.DEFAULT_TEST_NOW
import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.application.outbox.createRelayService
import dev.notypie.application.outbox.stubClaimLifecycle
import dev.notypie.impl.command.RATE_LIMITED_REASON
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.dto.MessagePublishFailedEvent
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.support.SendResult
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DebeziumLogTailingProcessorTest :
    BehaviorSpec({
        data class Fixture(
            val dispatcher: MessageDispatcher,
            val eventPublisher: ApplicationEventPublisher,
            val outboxRepository: MessageOutboxRepository,
            val processor: DebeziumLogTailingProcessor,
        )

        fun fixture(dispatchOk: Boolean = true, errorReason: String = "slack rejected"): Fixture {
            val dispatcher = mockk<MessageDispatcher>()
            val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
            val outboxRepository = mockk<MessageOutboxRepository>()
            outboxRepository.stubClaimLifecycle()
            every { dispatcher.dispatch(event = any()) } returns
                mockk(relaxed = true) {
                    every { ok } returns dispatchOk
                    every { this@mockk.errorReason } returns if (dispatchOk) "" else errorReason
                }
            return Fixture(
                dispatcher = dispatcher,
                eventPublisher = eventPublisher,
                outboxRepository = outboxRepository,
                processor =
                    DebeziumLogTailingProcessor(
                        outboxRepository = outboxRepository,
                        relayService =
                            createRelayService(
                                outboxRepository = outboxRepository,
                                messageDispatcher = dispatcher,
                                applicationEventPublisher = eventPublisher,
                            ),
                        clock = createFixedUtcClock(),
                    ),
            )
        }

        fun MessageOutboxRepository.currentRowIs(eventId: String, status: MessageStatus) {
            every { findById(eventId) } returns Optional.of(createOutboxRow(eventId = eventId, status = status))
        }

        fun MessageOutboxRepository.claimReturns(eventId: String, won: Int) {
            every { claimPending(eventId = eventId, attemptCount = 0, now = DEFAULT_TEST_NOW) } returns won
        }

        given("a PENDING insert whose row is still PENDING in the database") {
            val eventId = UUID.randomUUID().toString()
            val f = fixture()
            f.outboxRepository.currentRowIs(eventId = eventId, status = MessageStatus.PENDING)
            f.outboxRepository.claimReturns(eventId = eventId, won = 1)

            `when`("the record is consumed") {
                f.processor.consume(envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = eventId)))

                then("the row is claimed at the clock's time, dispatched once, and SUCCESS written for attempt 1") {
                    verify(exactly = 1) {
                        f.outboxRepository.claimPending(eventId = eventId, attemptCount = 0, now = DEFAULT_TEST_NOW)
                    }
                    verify(exactly = 1) { f.dispatcher.dispatch(event = any()) }
                    verify(exactly = 1) {
                        f.outboxRepository.completeClaim(
                            eventId = eventId,
                            attemptCount = 1,
                            status = MessageStatus.SUCCESS.name,
                            now = DEFAULT_TEST_NOW,
                        )
                    }
                    val published = slot<Any>()
                    verify(exactly = 1) { f.eventPublisher.publishEvent(capture(published)) }
                    published.captured.shouldBeInstanceOf<MessagePublishSuccessEvent>().eventId shouldBe
                        UUID.fromString(eventId)
                }
            }
        }

        given("an after-image written before V20, without attempt_count") {
            val eventId = UUID.randomUUID().toString()
            val f = fixture()
            f.outboxRepository.currentRowIs(eventId = eventId, status = MessageStatus.PENDING)
            f.outboxRepository.claimReturns(eventId = eventId, won = 1)

            `when`("the record is consumed") {
                f.processor.consume(
                    envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = eventId) - "attempt_count"),
                )

                then("it still maps to a row and is dispatched") {
                    verify(exactly = 1) { f.dispatcher.dispatch(event = any()) }
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
                    verify(exactly = 0) {
                        f.outboxRepository.claimPending(eventId = any(), attemptCount = any(), now = any())
                    }
                    verify(exactly = 0) { f.dispatcher.dispatch(event = any()) }
                    verify(exactly = 0) { f.eventPublisher.publishEvent(any()) }
                }
            }
        }

        given("a record whose claim is lost to another consumer") {
            val eventId = UUID.randomUUID().toString()
            val f = fixture()
            f.outboxRepository.currentRowIs(eventId = eventId, status = MessageStatus.PENDING)
            f.outboxRepository.claimReturns(eventId = eventId, won = 0)

            `when`("the record is consumed") {
                f.processor.consume(envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = eventId)))

                then("it is not dispatched") {
                    verify(exactly = 0) { f.dispatcher.dispatch(event = any()) }
                }
            }
        }

        given("a redelivered record whose row is IN_PROGRESS under another owner") {
            val eventId = UUID.randomUUID().toString()
            val f = fixture()
            f.outboxRepository.currentRowIs(eventId = eventId, status = MessageStatus.IN_PROGRESS)

            `when`("the record is redelivered") {
                f.processor.consume(envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = eventId)))

                then("it is skipped: the recovery sweep is the only owner of a stuck row") {
                    verify(exactly = 0) {
                        f.outboxRepository.claimPending(eventId = any(), attemptCount = any(), now = any())
                    }
                    verify(exactly = 0) { f.dispatcher.dispatch(event = any()) }
                    verify(exactly = 0) { f.eventPublisher.publishEvent(any()) }
                }
            }
        }

        given("a dispatch whose status write keeps failing") {
            val eventId = UUID.randomUUID().toString()
            val f = fixture()
            every { f.outboxRepository.findById(eventId) } returnsMany
                listOf(
                    Optional.of(createOutboxRow(eventId = eventId, status = MessageStatus.PENDING)),
                    Optional.of(
                        createOutboxRow(eventId = eventId, status = MessageStatus.IN_PROGRESS, attemptCount = 1),
                    ),
                )
            f.outboxRepository.claimReturns(eventId = eventId, won = 1)
            every {
                f.outboxRepository.completeClaim(eventId = eventId, attemptCount = any(), status = any(), now = any())
            } throws IllegalStateException("database unavailable")
            val envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = eventId))

            `when`("the record is consumed and then redelivered") {
                shouldNotThrowAny { f.processor.consume(envelope = envelope) }
                shouldNotThrowAny { f.processor.consume(envelope = envelope) }

                then("no exception reaches the container and the message is sent only once") {
                    verify(exactly = 1) { f.dispatcher.dispatch(event = any()) }
                    verify(exactly = 0) { f.eventPublisher.publishEvent(any()) }
                }
            }
        }

        given("a dispatch that fails") {
            val eventId = UUID.randomUUID().toString()
            val f = fixture(dispatchOk = false)
            f.outboxRepository.currentRowIs(eventId = eventId, status = MessageStatus.PENDING)
            f.outboxRepository.claimReturns(eventId = eventId, won = 1)

            `when`("the record is consumed") {
                f.processor.consume(envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = eventId)))

                then("FAILURE is written for the claim, a failure event is published, and consume returns") {
                    verify(exactly = 1) {
                        f.outboxRepository.completeClaim(
                            eventId = eventId,
                            attemptCount = 1,
                            status = MessageStatus.FAILURE.name,
                            now = DEFAULT_TEST_NOW,
                        )
                    }
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
            f.outboxRepository.claimReturns(eventId = eventId, won = 1)

            `when`("the record is consumed") {
                f.processor.consume(envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = eventId)))

                then("no status is written: the row stays IN_PROGRESS for the recovery sweep") {
                    verify(exactly = 0) {
                        f.outboxRepository.completeClaim(
                            eventId = any(),
                            attemptCount = any(),
                            status = any(),
                            now = any(),
                        )
                    }
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
                            after = createOutboxAfterImage(status = MessageStatus.IN_PROGRESS, attemptCount = 1),
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

        given("the CDC dead-letter recoverer") {
            data class DeadLetterFixture(
                val jsonTemplate: KafkaOperations<Any, Any>,
                val bytesTemplate: KafkaOperations<Any, Any>,
                val recoverer: DeadLetterPublishingRecoverer,
                val meterRegistry: SimpleMeterRegistry,
            )

            fun recovererWith(sendResult: () -> CompletableFuture<SendResult<Any, Any>>): DeadLetterFixture {
                val jsonTemplate = mockk<KafkaOperations<Any, Any>>()
                val bytesTemplate = mockk<KafkaOperations<Any, Any>>()
                listOf(jsonTemplate, bytesTemplate).forEach { template ->
                    every { template.isTransactional } returns false
                    every { template.send(any<ProducerRecord<Any, Any>>()) } answers { sendResult() }
                }
                val meterRegistry = SimpleMeterRegistry()
                return DeadLetterFixture(
                    jsonTemplate = jsonTemplate,
                    bytesTemplate = bytesTemplate,
                    recoverer =
                        cdcDeadLetterRecoverer(
                            jsonTemplate = jsonTemplate,
                            bytesTemplate = bytesTemplate,
                            meterRegistry = meterRegistry,
                        ),
                    meterRegistry = meterRegistry,
                )
            }

            fun SimpleMeterRegistry.dltFailures(): Double = counter(METRIC_DLT_PUBLISH_FAILURES).count()

            `when`("a record whose value could not be deserialized is recovered") {
                val raw = """{"payload":{"after":"not an outbox row"}}""".toByteArray()
                val (jsonTemplate, bytesTemplate, recoverer, meterRegistry) =
                    recovererWith(sendResult = { CompletableFuture.completedFuture(mockk(relaxed = true)) })

                recoverer.accept(
                    createCdcConsumerRecord(value = null, undeserializableValue = raw),
                    IllegalStateException("deserialization failed"),
                )

                then("the original bytes go through the byte-array template to <topic>-dlt with no fixed partition") {
                    verify(exactly = 1) {
                        bytesTemplate.send(
                            match<ProducerRecord<Any, Any>> {
                                it.topic() == "cdc.code_companion.outbox_message-dlt" &&
                                    it.partition() == null &&
                                    (it.value() as ByteArray).contentEquals(raw)
                            },
                        )
                    }
                    verify(exactly = 0) { jsonTemplate.send(any<ProducerRecord<Any, Any>>()) }
                    meterRegistry.dltFailures() shouldBe 0.0
                }
            }

            `when`("a deserialized envelope fails to parse") {
                val (jsonTemplate, bytesTemplate, recoverer) =
                    recovererWith(sendResult = { CompletableFuture.completedFuture(mockk(relaxed = true)) })

                recoverer.accept(
                    createCdcConsumerRecord(),
                    CdcRecordParseException(message = "Failed to parse CDC after-image"),
                )

                then("it goes through the JSON template to the same dead-letter topic") {
                    verify(exactly = 1) {
                        jsonTemplate.send(
                            match<ProducerRecord<Any, Any>> { it.topic() == "cdc.code_companion.outbox_message-dlt" },
                        )
                    }
                    verify(exactly = 0) { bytesTemplate.send(any<ProducerRecord<Any, Any>>()) }
                }
            }

            `when`("the dead-letter topic cannot be written") {
                val (_, _, recoverer, meterRegistry) =
                    recovererWith(sendResult = { CompletableFuture.failedFuture(KafkaException("unknown topic")) })

                then("recovery still completes, so the partition moves past the record, and the failure is counted") {
                    shouldNotThrowAny {
                        recoverer.accept(
                            createCdcConsumerRecord(value = null, undeserializableValue = "{}".toByteArray()),
                            IllegalStateException("deserialization failed"),
                        )
                    }
                    meterRegistry.dltFailures() shouldBe 1.0
                }
            }

            `when`("the send itself throws, as KafkaTemplate does when the metadata wait times out") {
                val (_, _, recoverer, meterRegistry) =
                    recovererWith(sendResult = { throw KafkaException("Send failed", TimeoutException("metadata")) })

                then("recovery still completes and the failure is counted once") {
                    shouldNotThrowAny {
                        recoverer.accept(
                            createCdcConsumerRecord(),
                            CdcRecordParseException(message = "Failed to parse CDC after-image"),
                        )
                    }
                    meterRegistry.dltFailures() shouldBe 1.0
                }
            }
        }

        given("the container-managed dead-letter recovery bean") {
            val jsonTemplate = mockk<KafkaTemplate<String, Any>>()
            every { jsonTemplate.isTransactional } returns false
            every { jsonTemplate.producerFactory.configurationProperties } returns
                mapOf(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to "127.0.0.1:1")

            `when`("it is built from the JSON template and the context shuts down") {
                val jsonProducerFactory = mockk<DefaultKafkaProducerFactory<Any, Any>>(relaxed = true)
                val bytesProducerFactory = mockk<DefaultKafkaProducerFactory<Any, Any>>(relaxed = true)
                val recovery =
                    CdcDeadLetterRecovery(
                        jsonTemplate = jsonTemplate,
                        meterRegistry = SimpleMeterRegistry(),
                        jsonProducerFactory = jsonProducerFactory,
                        bytesProducerFactory = bytesProducerFactory,
                    )
                recovery.destroy()

                then("it dead-letters through both templates and closes both producers it owns") {
                    recovery.recoverer.shouldBeInstanceOf<DeadLetterPublishingRecoverer>()
                    verify(exactly = 1) { jsonProducerFactory.destroy() }
                    verify(exactly = 1) { bytesProducerFactory.destroy() }
                }
            }

            `when`("its producer factories are derived from the JSON template") {
                val json = deadLetterProducerFactory(jsonTemplate = jsonTemplate)
                val bytes =
                    deadLetterProducerFactory(
                        jsonTemplate = jsonTemplate,
                        valueSerializer = ByteArraySerializer::class.java,
                    )

                then(
                    "both keep the JSON producer's settings, block at most a few seconds, and only bytes swaps serializer",
                ) {
                    listOf(json, bytes).forEach { factory ->
                        factory.configurationProperties[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] shouldBe "127.0.0.1:1"
                        factory.configurationProperties[ProducerConfig.MAX_BLOCK_MS_CONFIG] shouldBe
                            DEAD_LETTER_MAX_BLOCK.toMillis()
                    }
                    json.configurationProperties[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] shouldBe null
                    bytes.configurationProperties[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] shouldBe
                        ByteArraySerializer::class.java
                }
            }

            `when`("no KafkaTemplate exists") {
                val meterRegistry = SimpleMeterRegistry()
                val recovery = CdcDeadLetterRecovery(jsonTemplate = null, meterRegistry = meterRegistry)

                then("records are logged, dropped and counted, and shutdown has nothing to close") {
                    shouldNotThrowAny {
                        recovery.recoverer.accept(createCdcConsumerRecord(), IllegalStateException("parse"))
                        recovery.destroy()
                    }
                    meterRegistry.counter(METRIC_DLT_PUBLISH_FAILURES).count() shouldBe 1.0
                }
            }
        }
    })
