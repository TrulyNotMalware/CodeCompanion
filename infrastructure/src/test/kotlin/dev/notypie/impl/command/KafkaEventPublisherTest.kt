package dev.notypie.impl.command

import dev.notypie.domain.command.DefaultEventQueue
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import java.time.Duration
import java.util.UUID

private const val TEST_TOPIC = "test-event-topic"

data class TestKafkaPayload(
    val value: String,
    override val eventId: UUID = UUID.randomUUID(),
) : EventPayload

data class TestKafkaCommandEvent(
    override val idempotencyKey: UUID = UUID.randomUUID(),
    override val payload: TestKafkaPayload,
    override val destination: String,
    override val isInternal: Boolean,
    override val timestamp: Long = System.currentTimeMillis(),
    override val name: String = "TestKafkaEvent",
    override val type: CommandDetailType = CommandDetailType.NOTHING,
) : CommandEvent<TestKafkaPayload>

@SpringBootTest
@EmbeddedKafka(
    topics = [TEST_TOPIC],
    partitions = 1,
    bootstrapServersProperty = "spring.kafka.bootstrap-servers",
)
@ApplyExtension(extensions = [SpringExtension::class])
@Suppress("SpringJavaInjectionPointsAutowiringInspection")
class KafkaEventPublisherTest
    @Autowired
    constructor(
        private val kafkaTemplate: KafkaTemplate<String, Any>,
        private val embeddedKafkaBroker: EmbeddedKafkaKraftBroker,
    ) : BehaviorSpec({
            val applicationEventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
            val publisher =
                KafkaEventPublisher(
                    kafkaTemplate = kafkaTemplate,
                    applicationEventPublisher = applicationEventPublisher,
                )

            fun createTestConsumer(): org.apache.kafka.clients.consumer.Consumer<String, Any> {
                val consumerProps =
                    KafkaTestUtils.consumerProps(embeddedKafkaBroker, "test-group-${UUID.randomUUID()}", true)
                consumerProps[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
                consumerProps[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = JacksonJsonDeserializer::class.java
                consumerProps["spring.json.trusted.packages"] = "*"
                consumerProps[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
                val factory = DefaultKafkaConsumerFactory<String, Any>(consumerProps)
                return factory.createConsumer()
            }

            given("publishEvent with external event") {
                val consumer = createTestConsumer()
                consumer.subscribe(listOf(TEST_TOPIC))

                val idempotencyKey = UUID.randomUUID()
                val externalEvent =
                    TestKafkaCommandEvent(
                        idempotencyKey = idempotencyKey,
                        destination = TEST_TOPIC,
                        isInternal = false,
                        payload = TestKafkaPayload(value = "external-data"),
                    )

                val events = DefaultEventQueue<CommandEvent<EventPayload>>()
                events.offer(event = externalEvent)

                `when`("publishing") {
                    publisher.publishEvent(events = events)

                    then("kafka consumer should receive the message with correct key") {
                        val records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10))
                        records.count() shouldNotBe 0

                        val record = records.first()
                        record.topic() shouldBe TEST_TOPIC
                        record.key() shouldBe idempotencyKey.toString()
                    }

                    then("should not publish via applicationEventPublisher") {
                        verify(exactly = 0) { applicationEventPublisher.publishEvent(eq(externalEvent)) }
                    }
                }

                consumer.close()
            }

            given("publishEvent with internal event") {
                val internalEvent =
                    TestKafkaCommandEvent(
                        destination = "",
                        isInternal = true,
                        payload = TestKafkaPayload(value = "internal-data"),
                    )

                val events = DefaultEventQueue<CommandEvent<EventPayload>>()
                events.offer(event = internalEvent)

                `when`("publishing") {
                    publisher.publishEvent(events = events)

                    then("should publish via applicationEventPublisher") {
                        verify(exactly = 1) { applicationEventPublisher.publishEvent(eq(internalEvent)) }
                    }
                }
            }

            given("publishEvent with mixed events") {
                val consumer = createTestConsumer()
                consumer.subscribe(listOf(TEST_TOPIC))

                val externalEvent =
                    TestKafkaCommandEvent(
                        destination = TEST_TOPIC,
                        isInternal = false,
                        payload = TestKafkaPayload(value = "mixed-external"),
                    )
                val internalEvent =
                    TestKafkaCommandEvent(
                        destination = "",
                        isInternal = true,
                        payload = TestKafkaPayload(value = "mixed-internal"),
                    )

                val events = DefaultEventQueue<CommandEvent<EventPayload>>()
                events.offer(event = internalEvent)
                events.offer(event = externalEvent)

                `when`("publishing") {
                    publisher.publishEvent(events = events)

                    then("kafka consumer should receive external event") {
                        val records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10))
                        val matchingRecord =
                            records.firstOrNull { it.key() == externalEvent.idempotencyKey.toString() }
                        matchingRecord shouldNotBe null
                        matchingRecord!!.topic() shouldBe TEST_TOPIC
                    }

                    then("should publish internal event via applicationEventPublisher") {
                        verify(exactly = 1) { applicationEventPublisher.publishEvent(eq(internalEvent)) }
                    }
                }

                consumer.close()
            }

            given("publishEvent with empty queue") {
                val emptyAppPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
                val emptyPublisher =
                    KafkaEventPublisher(
                        kafkaTemplate = kafkaTemplate,
                        applicationEventPublisher = emptyAppPublisher,
                    )
                val events = DefaultEventQueue<CommandEvent<EventPayload>>()

                `when`("publishing") {
                    emptyPublisher.publishEvent(events = events)

                    then("should not publish anything") {
                        verify(exactly = 0) { emptyAppPublisher.publishEvent(any()) }
                    }
                }
            }
        })
