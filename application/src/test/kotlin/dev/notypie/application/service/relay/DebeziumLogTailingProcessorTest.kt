package dev.notypie.application.service.relay

import ch.qos.logback.classic.Level
import dev.notypie.application.configurations.CdcDeadLetterRecovery
import dev.notypie.application.configurations.CountingRecordRecoverer
import dev.notypie.application.configurations.DEAD_LETTER_HANDOFFS_METRIC
import dev.notypie.application.configurations.DEAD_LETTER_MAX_BLOCK
import dev.notypie.application.configurations.DEAD_LETTER_PUBLISH_FAILURES_METRIC
import dev.notypie.application.configurations.PoisonOnlyDeadLetterRecoverer
import dev.notypie.application.configurations.cdcDeadLetterRecoverer
import dev.notypie.application.configurations.cdcErrorHandler
import dev.notypie.application.outbox.DEFAULT_TEST_NOW
import dev.notypie.application.outbox.createFixedUtcClock
import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.application.outbox.createRelayService
import dev.notypie.application.outbox.stubClaimLifecycle
import dev.notypie.impl.command.RATE_LIMITED_REASON
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.logging.captureLogs
import dev.notypie.logging.messagesAt
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.dto.MessageDeliveredEvent
import dev.notypie.repository.outbox.dto.MessagePublishFailedEvent
import dev.notypie.repository.outbox.dto.MessagePublishSuccessEvent
import dev.notypie.repository.outbox.dto.OutboxUpdateEvent
import dev.notypie.repository.outbox.schema.MessageStatus
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.mock.MockConsumerFactory
import org.springframework.kafka.support.SendResult
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private class FailingColumn(
    private val error: Error,
) {
    val value: String
        get() = throw error
}

private fun failingColumn(error: Error): Any = FailingColumn(error = error)

class DebeziumLogTailingProcessorTest :
    BehaviorSpec({
        val loggerName = DebeziumLogTailingProcessor::class.java.name

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
                    val published = mutableListOf<Any>()
                    verify(exactly = 2) { f.eventPublisher.publishEvent(capture(published)) }
                    published.first().shouldBeInstanceOf<MessageDeliveredEvent>().eventId shouldBe
                        UUID.fromString(eventId)
                    published.last().shouldBeInstanceOf<MessagePublishSuccessEvent>().eventId shouldBe
                        UUID.fromString(eventId)
                }
            }
        }

        given("a PENDING after-image whose outbox row no longer exists") {
            val eventId = UUID.randomUUID().toString()
            val f = fixture()
            every { f.outboxRepository.findById(eventId) } returns Optional.empty()

            `when`("the record is consumed") {
                val warnings =
                    captureLogs(loggerName = loggerName) {
                        f.processor.consume(
                            envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = eventId)),
                        )
                    }.messagesAt(level = Level.WARN)

                then("nothing is claimed or dispatched") {
                    verify(exactly = 0) {
                        f.outboxRepository.claimPending(eventId = any(), attemptCount = any(), now = any())
                    }
                    verify(exactly = 0) { f.dispatcher.dispatch(event = any()) }
                }

                then("the processor's logger warns about the missing row") {
                    warnings.single() shouldStartWith "Outbox row missing for eventId=$eventId"
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
                    verify(exactly = 0) { f.eventPublisher.publishEvent(any<Any>()) }
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
                    verify(exactly = 0) { f.eventPublisher.publishEvent(any<Any>()) }
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

                then("no exception reaches the container, the message is sent only once and no outcome is announced") {
                    verify(exactly = 1) { f.dispatcher.dispatch(event = any()) }
                    verify(exactly = 1) { f.eventPublisher.publishEvent(ofType<MessageDeliveredEvent>()) }
                    verify(exactly = 0) { f.eventPublisher.publishEvent(ofType<OutboxUpdateEvent>()) }
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
                    verify(exactly = 0) { f.eventPublisher.publishEvent(any<Any>()) }
                }
            }
        }

        given("CDC events this processor must ignore") {
            val f = fixture()

            `when`("the record is a tombstone") {
                val logs = captureLogs(loggerName = loggerName) { f.processor.consume(envelope = null) }

                then("it is skipped without touching the database") {
                    verify(exactly = 0) { f.outboxRepository.findById(any()) }
                }

                then("it logs no warning, since every retention purge emits one per deleted row") {
                    logs.eventsAt(level = Level.WARN).shouldBeEmpty()
                }
            }

            `when`("the record is a delete event with no after-image") {
                f.processor.consume(
                    envelope = createCdcEnvelope(after = null, op = "d"),
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

        given("records whose handling hits an Error") {
            `when`("converting the after-image throws an Error") {
                val f = fixture()
                val eventId = UUID.randomUUID().toString()
                val thrown = NotImplementedError("unfinished column")
                val (wrapped, logs) =
                    captureLogs(loggerName = loggerName) {
                        shouldThrowExactly<ListenerErrorException> {
                            f.processor.consume(
                                envelope =
                                    createCdcEnvelope(
                                        after =
                                            createOutboxAfterImage(eventId = eventId) +
                                                ("payload" to failingColumn(error = thrown)),
                                    ),
                                partition = 3,
                                offset = 42L,
                            )
                        }
                    }

                then(
                    "it reaches the container as a ListenerErrorException carrying the same Error, logged with the record",
                ) {
                    wrapped.cause shouldBeSameInstanceAs thrown
                    logs.messagesAt(level = Level.ERROR).single() shouldContain "partition=3 offset=42 eventId=$eventId"
                    verify(exactly = 0) { f.outboxRepository.findById(any()) }
                }
            }

            `when`("the dispatch throws an Error") {
                val f = fixture()
                val eventId = UUID.randomUUID().toString()
                val thrown = StackOverflowError("deep")
                f.outboxRepository.currentRowIs(eventId = eventId, status = MessageStatus.PENDING)
                f.outboxRepository.claimReturns(eventId = eventId, won = 1)
                every { f.dispatcher.dispatch(event = any()) } throws thrown

                then("it reaches the container as a ListenerErrorException carrying the same Error") {
                    shouldThrowExactly<ListenerErrorException> {
                        f.processor.consume(
                            envelope = createCdcEnvelope(after = createOutboxAfterImage(eventId = eventId)),
                        )
                    }.cause shouldBeSameInstanceAs thrown
                }
            }
        }

        given("a listener container in front of the processor, with the CDC error handler") {
            val topic = "cdc.code_companion.outbox_message"
            val partition = TopicPartition(topic, 0)
            val parseFailingId = UUID.randomUUID().toString()
            val dispatchFailingId = UUID.randomUUID().toString()
            val nextId = UUID.randomUUID().toString()
            val parseError = NotImplementedError("unfinished column")
            val dispatchError = StackOverflowError("deep")
            val outboxRepository = mockk<MessageOutboxRepository>()
            listOf(dispatchFailingId, nextId).forEach { eventId ->
                outboxRepository.currentRowIs(eventId = eventId, status = MessageStatus.PENDING)
                outboxRepository.claimReturns(eventId = eventId, won = 1)
            }
            val nextDispatched = CountDownLatch(1)
            val failingDispatches = AtomicInteger(0)
            val relayService =
                mockk<MessageRelayService> {
                    every { dispatchClaimed(claim = match { it.row.eventId == dispatchFailingId }) } answers {
                        failingDispatches.incrementAndGet()
                        throw dispatchError
                    }
                    every { dispatchClaimed(claim = match { it.row.eventId == nextId }) } answers
                        { nextDispatched.countDown() }
                }
            val processor =
                DebeziumLogTailingProcessor(
                    outboxRepository = outboxRepository,
                    relayService = relayService,
                    clock = createFixedUtcClock(),
                )
            val deadLettered = CopyOnWriteArrayList<Pair<Long, Exception?>>()
            val consumer = MockConsumer<String, Any>("earliest")
            consumer.updateBeginningOffsets(mapOf(partition to 0L))
            consumer.schedulePollTask { consumer.rebalance(listOf(partition)) }
            listOf(
                createOutboxAfterImage(eventId = parseFailingId) +
                    ("payload" to failingColumn(error = parseError)),
                createOutboxAfterImage(eventId = dispatchFailingId),
                createOutboxAfterImage(eventId = nextId),
            ).forEachIndexed { offset, afterImage ->
                consumer.schedulePollTask {
                    consumer.addRecord(
                        ConsumerRecord<String, Any>(
                            topic,
                            0,
                            offset.toLong(),
                            "key-$offset",
                            createCdcEnvelope(after = afterImage),
                        ),
                    )
                }
            }
            val container =
                KafkaMessageListenerContainer(
                    MockConsumerFactory { consumer },
                    ContainerProperties(topic).apply {
                        ackMode = ContainerProperties.AckMode.RECORD
                        setGroupId("cdc-error-test")
                        setMessageListener(
                            MessageListener<String, Any> { record ->
                                processor.consume(
                                    envelope = record.value() as Envelope?,
                                    partition = record.partition(),
                                    offset = record.offset(),
                                )
                            },
                        )
                    },
                )
            container.commonErrorHandler =
                cdcErrorHandler(
                    recoverer =
                        PoisonOnlyDeadLetterRecoverer(
                            deadLetter =
                                ConsumerAwareRecordRecoverer { record, _, exception ->
                                    deadLettered += record.offset() to exception
                                },
                        ),
                )

            `when`("a parse-time Error and a dispatch Error arrive before an ordinary record") {
                container.start()
                val consumedNext = nextDispatched.await(10L, TimeUnit.SECONDS)
                val stillRunning = container.isRunning
                container.stop()

                then(
                    "both go to the dead-letter topic once, unretried, and the container goes on with the next record",
                ) {
                    consumedNext shouldBe true
                    stillRunning shouldBe true
                    deadLettered.map { it.first } shouldBe listOf(0L, 1L)
                    deadLettered.forEach { (_, exception) ->
                        generateSequence<Throwable>(exception) { it.cause }
                            .filterIsInstance<ListenerErrorException>()
                            .toList() shouldHaveSize 1
                    }
                    generateSequence<Throwable>(deadLettered[0].second) { it.cause }.last() shouldBeSameInstanceAs
                        parseError
                    generateSequence<Throwable>(deadLettered[1].second) { it.cause }.last() shouldBeSameInstanceAs
                        dispatchError
                    failingDispatches.get() shouldBe 1
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

            fun SimpleMeterRegistry.publishFailures(): Double =
                find(DEAD_LETTER_PUBLISH_FAILURES_METRIC).counter()?.count() ?: 0.0

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
                    meterRegistry.publishFailures() shouldBe 0.0
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
                    meterRegistry.publishFailures() shouldBe 1.0
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
                    meterRegistry.publishFailures() shouldBe 1.0
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
                    recovery.recoverer.deadLetter
                        .shouldBeInstanceOf<CountingRecordRecoverer>()
                        .delegate
                        .shouldBeInstanceOf<DeadLetterPublishingRecoverer>()
                    verify(exactly = 1) { jsonProducerFactory.destroy() }
                    verify(exactly = 1) { bytesProducerFactory.destroy() }
                }
            }

            `when`("its producer factories are derived from the JSON template") {
                val recovery = CdcDeadLetterRecovery(jsonTemplate = jsonTemplate, meterRegistry = SimpleMeterRegistry())
                val json = recovery.jsonProducerFactory
                val bytes = recovery.bytesProducerFactory

                then("both keep the JSON producer's settings and block a few seconds; only bytes swaps serializer") {
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
        }

        given("the poison-only recoverer in front of the dead-letter publisher") {
            val record = createCdcConsumerRecord()
            val consumer = mockk<Consumer<*, *>>()

            `when`("the listener failed to parse the after-image") {
                val deadLetter = mockk<ConsumerAwareRecordRecoverer>(relaxed = true)
                val failure =
                    ListenerExecutionFailedException(
                        "listener failed",
                        CdcRecordParseException(message = "bad after-image", cause = IllegalStateException("parse")),
                    )
                PoisonOnlyDeadLetterRecoverer(deadLetter = deadLetter).accept(record, consumer, failure)

                then("the record goes to the dead-letter topic") {
                    verify(exactly = 1) { deadLetter.accept(record, consumer, failure) }
                }
            }

            `when`("the listener failed because the database was unreachable") {
                val deadLetter = mockk<ConsumerAwareRecordRecoverer>(relaxed = true)
                val failure =
                    ListenerExecutionFailedException("listener failed", DataAccessResourceFailureException("db down"))

                then("the record is acknowledged without dead-lettering, its row is left to the recovery sweep") {
                    shouldNotThrowAny {
                        PoisonOnlyDeadLetterRecoverer(
                            deadLetter = deadLetter,
                        ).accept(record, consumer, failure)
                    }
                    verify(exactly = 0) { deadLetter.accept(any(), any(), any()) }
                }
            }
        }

        given("a counting recoverer over a consumer-aware dead-letter publisher") {
            val record = createCdcConsumerRecord()
            val consumer = mockk<Consumer<*, *>>()
            val exception = IllegalStateException("parse")

            `when`("the delegate hands the record to the dead-letter topic") {
                val meterRegistry = SimpleMeterRegistry()
                val delegate = mockk<ConsumerAwareRecordRecoverer>()
                every { delegate.accept(record, consumer, exception) } just runs
                val recoverer = CountingRecordRecoverer(delegate = delegate, meterRegistry = meterRegistry)

                recoverer.accept(record, consumer, exception)

                then("the delegate gets the consumer and the record is counted once by topic") {
                    verify(exactly = 1) { delegate.accept(record, consumer, exception) }
                    meterRegistry
                        .get(DEAD_LETTER_HANDOFFS_METRIC)
                        .tags("topic", record.topic())
                        .counter()
                        .count() shouldBe 1.0
                }
            }

            `when`(
                "a delegate throws synchronously (the wrapper contract; the production publisher logs send failures instead)",
            ) {
                val meterRegistry = SimpleMeterRegistry()
                val delegate = mockk<ConsumerAwareRecordRecoverer>()
                every { delegate.accept(record, consumer, exception) } throws KafkaException("send timed out")
                val recoverer = CountingRecordRecoverer(delegate = delegate, meterRegistry = meterRegistry)

                then("nothing is counted for the failed attempt") {
                    shouldThrow<KafkaException> { recoverer.accept(record, consumer, exception) }
                    meterRegistry.find(DEAD_LETTER_HANDOFFS_METRIC).counter() shouldBe null
                }
            }
        }
    })
