package dev.notypie.application.configurations

import dev.notypie.application.configurations.conditions.OnCdcConsumer
import dev.notypie.application.configurations.conditions.OnKafkaEventPublisher
import dev.notypie.application.service.relay.CdcRecordParseException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.common.KeyValues
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
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

@Configuration
@Conditional(OnCdcConsumer::class)
@EnableKafka
@Import(KafkaConsumerConfiguration::class, KafkaObservationConvention::class)
class CdcConsumerConfiguration

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

    // RECORD ack: the offset moves only after the listener returns for that record, so a crash mid-record
    // redelivers it and the processor's current-status check decides whether to send again. This relies on
    // `enable-auto-commit: false` in the profile; with auto-commit on, the client commits behind our back.
    @Bean
    @ConditionalOnMissingBean(ConcurrentKafkaListenerContainerFactory::class)
    fun concurrentKafkaListenerContainerFactory() =
        ConcurrentKafkaListenerContainerFactory<String, Any>().apply {
            containerProperties.isObservationEnabled = true
            containerProperties.isMicrometerEnabled = false
            containerProperties.ackMode = ContainerProperties.AckMode.RECORD
            setCommonErrorHandler(cdcErrorHandler())
            setConsumerFactory(consumerFactory())
            containerProperties.setObservationConvention(convention)
        }

    // Two quick retries for transient failures, then the record is parked on <topic>.DLT instead of being
    // skipped; parse failures are deterministic and go straight there. Without a producer (CDC consumer
    // with the application-event publisher) the recoverer degrades to an ERROR log.
    private fun cdcErrorHandler(): DefaultErrorHandler {
        val kafkaTemplate = kafkaTemplateProvider.ifAvailable
        val recoverer =
            if (kafkaTemplate != null) {
                DeadLetterPublishingRecoverer(kafkaTemplate)
            } else {
                ConsumerRecordRecoverer { record, exception ->
                    logger.error(exception) {
                        "No KafkaTemplate for a dead-letter topic; dropping CDC record " +
                            "topic=${record.topic()} partition=${record.partition()} offset=${record.offset()}"
                    }
                }
            }
        return DefaultErrorHandler(recoverer, FixedBackOff(1_000L, 2L)).apply {
            addNotRetryableExceptions(CdcRecordParseException::class.java)
        }
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
