package dev.notypie.application.configurations

import dev.notypie.application.configurations.conditions.OnCdcConsumer
import dev.notypie.application.configurations.conditions.OnKafkaEventPublisher
import dev.notypie.application.service.relay.CdcRecordParseException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.common.KeyValues
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.*
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.micrometer.KafkaListenerObservation
import org.springframework.kafka.support.micrometer.KafkaListenerObservationConvention
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
import org.springframework.util.backoff.FixedBackOff

private val logger = KotlinLogging.logger { }

private const val DEAD_LETTER_TOPIC_SUFFIX = "-dlt"

const val DEAD_LETTER_RECORDS_METRIC = "kafka.dead.letter.records"

internal fun deadLetterTopic(topic: String): String = "$topic$DEAD_LETTER_TOPIC_SUFFIX"

internal fun cdcDeadLetterRecoverer(
    jsonTemplate: KafkaOperations<*, *>,
    bytesTemplate: KafkaOperations<*, *>,
): DeadLetterPublishingRecoverer =
    DeadLetterPublishingRecoverer(
        linkedMapOf<Class<*>, KafkaOperations<*, *>>(
            ByteArray::class.java to bytesTemplate,
            Any::class.java to jsonTemplate,
        ),
    ) { record, _ -> TopicPartition(deadLetterTopic(topic = record.topic()), -1) }
        .apply { setFailIfSendResultIsError(false) }

internal fun deadLetterBytesProducerFactory(
    jsonTemplate: KafkaTemplate<String, Any>,
): DefaultKafkaProducerFactory<Any, ByteArray> =
    DefaultKafkaProducerFactory(
        jsonTemplate.producerFactory.configurationProperties +
            (ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java),
    )

// Consumer-aware so DeadLetterPublishingRecoverer still receives the consumer (original group-id header).
class CountingRecordRecoverer(
    val delegate: ConsumerAwareRecordRecoverer,
    private val meterRegistry: MeterRegistry,
    private val outcome: String,
) : ConsumerAwareRecordRecoverer {
    override fun accept(record: ConsumerRecord<*, *>, consumer: Consumer<*, *>?, exception: Exception?) {
        delegate.accept(record, consumer, exception)
        meterRegistry.counter(DEAD_LETTER_RECORDS_METRIC, "topic", record.topic(), "outcome", outcome).increment()
    }
}

class CdcDeadLetterRecovery(
    jsonTemplate: KafkaTemplate<String, Any>?,
    meterRegistry: MeterRegistry,
    private val bytesProducerFactory: DefaultKafkaProducerFactory<Any, ByteArray>? =
        jsonTemplate?.let { deadLetterBytesProducerFactory(jsonTemplate = it) },
) : DisposableBean {
    val recoverer: CountingRecordRecoverer =
        if (jsonTemplate == null || bytesProducerFactory == null) {
            CountingRecordRecoverer(
                delegate =
                    ConsumerAwareRecordRecoverer { record, _, exception ->
                        logger.error(exception) {
                            "No KafkaTemplate for a dead-letter topic; dropping CDC record " +
                                "topic=${record.topic()} partition=${record.partition()} offset=${record.offset()}"
                        }
                    },
                meterRegistry = meterRegistry,
                outcome = "dropped",
            )
        } else {
            CountingRecordRecoverer(
                delegate =
                    cdcDeadLetterRecoverer(
                        jsonTemplate = jsonTemplate,
                        bytesTemplate = KafkaTemplate(bytesProducerFactory),
                    ),
                meterRegistry = meterRegistry,
                outcome = "dead_lettered",
            )
        }

    override fun destroy() {
        bytesProducerFactory?.destroy()
    }
}

@Configuration
@Conditional(OnCdcConsumer::class)
@EnableKafka
@Import(KafkaConsumerConfiguration::class, KafkaObservationConvention::class)
class CdcConsumerConfiguration {
    @Bean
    fun cdcDeadLetterTopic(appConfig: AppConfig): NewTopic =
        TopicBuilder.name(deadLetterTopic(topic = appConfig.mode.cdc.topic)).build()
}

@Configuration
@Conditional(OnKafkaEventPublisher::class)
@EnableKafka
@Import(
    KafkaProducerConfiguration::class,
    KafkaObservationConvention::class,
    KafkaConsumerConfiguration::class,
)
class KafkaEventPublisherConfiguration

class KafkaConsumerConfiguration(
    private val convention: KafkaObservationConvention,
    private val kafkaProperties: KafkaProperties,
    private val kafkaTemplateProvider: ObjectProvider<KafkaTemplate<String, Any>>,
) {
    // Without this, a bad record throws inside poll() and wedges the consumer on that offset forever.
    @Bean
    @ConditionalOnMissingBean(ConsumerFactory::class)
    fun consumerFactory(): ConsumerFactory<String, Any> {
        val properties = kafkaProperties.buildConsumerProperties()
        properties[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG]?.let { delegate ->
            properties[ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS] = delegate
            properties[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = ErrorHandlingDeserializer::class.java
        }
        properties[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG]?.let { delegate ->
            properties[ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS] = delegate
            properties[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = ErrorHandlingDeserializer::class.java
        }
        return DefaultKafkaConsumerFactory(properties)
    }

    @Bean
    fun cdcDeadLetterRecovery(meterRegistry: MeterRegistry): CdcDeadLetterRecovery =
        CdcDeadLetterRecovery(jsonTemplate = kafkaTemplateProvider.ifAvailable, meterRegistry = meterRegistry)

    // RECORD ack assumes `enable-auto-commit: false` in every CDC profile; auto-commit would commit in-flight records.
    @Bean
    @ConditionalOnMissingBean(ConcurrentKafkaListenerContainerFactory::class)
    fun concurrentKafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, Any>,
        cdcDeadLetterRecovery: CdcDeadLetterRecovery,
    ) = ConcurrentKafkaListenerContainerFactory<String, Any>().apply {
        containerProperties.isObservationEnabled = true
        containerProperties.isMicrometerEnabled = false
        containerProperties.ackMode = ContainerProperties.AckMode.RECORD
        setCommonErrorHandler(
            DefaultErrorHandler(cdcDeadLetterRecovery.recoverer, FixedBackOff(1_000L, 2L)).apply {
                addNotRetryableExceptions(CdcRecordParseException::class.java)
            },
        )
        setConsumerFactory(consumerFactory)
        containerProperties.setObservationConvention(convention)
    }
}

class KafkaProducerConfiguration(
    private val kafkaProperties: KafkaProperties,
) {
    @Bean
    @ConditionalOnMissingBean(ProducerFactory::class)
    fun producerFactory(): ProducerFactory<String, Any> =
        DefaultKafkaProducerFactory(kafkaProperties.buildProducerProperties())

    @Bean
    @ConditionalOnMissingBean(KafkaTemplate::class)
    fun kafkaTemplate(producerFactory: ProducerFactory<String, Any>): KafkaTemplate<String, Any> =
        KafkaTemplate(producerFactory).apply {
            setObservationEnabled(true)
            setMicrometerEnabled(false)
        }
}

class KafkaObservationConvention : KafkaListenerObservationConvention {
    override fun getName(): String = "code.companion.listener"

    override fun getContextualName(context: KafkaRecordReceiverContext) = context.source + " receive"

    override fun getLowCardinalityKeyValues(context: KafkaRecordReceiverContext): KeyValues =
        KeyValues.of(KafkaListenerObservation.ListenerLowCardinalityTags.LISTENER_ID.asString(), context.listenerId)
}
