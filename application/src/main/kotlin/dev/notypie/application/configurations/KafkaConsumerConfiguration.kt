package dev.notypie.application.configurations

import dev.notypie.application.configurations.conditions.OnCdcConsumer
import dev.notypie.application.configurations.conditions.OnKafkaEventPublisher
import dev.notypie.application.service.relay.CdcRecordParseException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.common.KeyValues
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
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
import org.springframework.kafka.listener.ConsumerRecordRecoverer
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

class CdcDeadLetterRecovery(
    jsonTemplate: KafkaTemplate<String, Any>?,
    private val bytesProducerFactory: DefaultKafkaProducerFactory<Any, ByteArray>? =
        jsonTemplate?.let { deadLetterBytesProducerFactory(jsonTemplate = it) },
) : DisposableBean {
    val recoverer: ConsumerRecordRecoverer =
        if (jsonTemplate == null || bytesProducerFactory == null) {
            ConsumerRecordRecoverer { record, exception ->
                logger.error(exception) {
                    "No KafkaTemplate for a dead-letter topic; dropping CDC record " +
                        "topic=${record.topic()} partition=${record.partition()} offset=${record.offset()}"
                }
            }
        } else {
            cdcDeadLetterRecoverer(jsonTemplate = jsonTemplate, bytesTemplate = KafkaTemplate(bytesProducerFactory))
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
    fun cdcDeadLetterRecovery(): CdcDeadLetterRecovery =
        CdcDeadLetterRecovery(jsonTemplate = kafkaTemplateProvider.ifAvailable)

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
        DefaultKafkaProducerFactory(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafkaProperties.bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to kafkaProperties.producer.keySerializer,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to kafkaProperties.producer.valueSerializer,
            ),
        )

    @Bean
    @ConditionalOnMissingBean(KafkaTemplate::class)
    fun kafkaTemplate(): KafkaTemplate<String, Any> =
        KafkaTemplate(producerFactory()).apply {
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
