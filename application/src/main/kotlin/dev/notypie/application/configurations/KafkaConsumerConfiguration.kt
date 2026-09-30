package dev.notypie.application.configurations

import dev.notypie.application.configurations.conditions.OnCdcConsumer
import dev.notypie.application.configurations.conditions.OnKafkaEventPublisher
import dev.notypie.application.service.relay.CdcRecordParseException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.common.KeyValues
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
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
import org.springframework.kafka.support.SendResult
import org.springframework.kafka.support.micrometer.KafkaListenerObservation
import org.springframework.kafka.support.micrometer.KafkaListenerObservationConvention
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
import org.springframework.util.backoff.FixedBackOff
import java.time.Duration
import java.util.concurrent.CompletableFuture

private val logger = KotlinLogging.logger { }

private const val DEAD_LETTER_TOPIC_SUFFIX = "-dlt"

// One record's worst case in the normal case (relay AGENTS.md "Per-record time budget"). The lifecycle phase
// (spring.lifecycle.timeout-per-shutdown-phase) and the pod grace period are sized on top of it.
internal val CDC_LISTENER_SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(60L)

internal fun deadLetterTopic(topic: String): String = "$topic$DEAD_LETTER_TOPIC_SUFFIX"

// The send blocks the listener thread while it waits for topic metadata (kafka-clients' default is 60 s, per record).
internal val DEAD_LETTER_MAX_BLOCK: Duration = Duration.ofSeconds(5L)

internal const val METRIC_DLT_PUBLISH_FAILURES = "codecompanion.cdc.dlt.publish.failures"

// setFailIfSendResultIsError(false) stays: a failed dead-letter send must not redeliver the partition forever, and the
// outbox row is still PENDING for the recovery sweep. What it loses is the forensic copy, so every failure is counted.
internal fun cdcDeadLetterRecoverer(
    jsonTemplate: KafkaOperations<*, *>,
    bytesTemplate: KafkaOperations<*, *>,
    meterRegistry: MeterRegistry,
): DeadLetterPublishingRecoverer =
    MeteredDeadLetterPublishingRecoverer(
        templates =
            linkedMapOf<Class<*>, KafkaOperations<*, *>>(
                ByteArray::class.java to bytesTemplate,
                Any::class.java to jsonTemplate,
            ),
        failures = meterRegistry.counter(METRIC_DLT_PUBLISH_FAILURES),
    ).apply { setFailIfSendResultIsError(false) }

// Both dead-letter templates get their own producer so max.block.ms stays bounded without touching the app's template.
internal fun deadLetterProducerFactory(
    jsonTemplate: KafkaTemplate<String, Any>,
    valueSerializer: Class<*>? = null,
): DefaultKafkaProducerFactory<Any, Any> =
    DefaultKafkaProducerFactory(
        jsonTemplate.producerFactory.configurationProperties +
            (ProducerConfig.MAX_BLOCK_MS_CONFIG to DEAD_LETTER_MAX_BLOCK.toMillis()) +
            listOfNotNull(valueSerializer?.let { ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to it }),
    )

private class MeteredDeadLetterPublishingRecoverer(
    templates: Map<Class<*>, KafkaOperations<*, *>>,
    private val failures: Counter,
) : DeadLetterPublishingRecoverer(
        templates,
        { record, _ -> TopicPartition(deadLetterTopic(topic = record.topic()), -1) },
    ) {
    // The library catches a throwing send and only logs an async failure, so the template is wrapped to see both.
    override fun publish(
        outRecord: ProducerRecord<Any, Any>,
        kafkaTemplate: KafkaOperations<Any, Any>,
        inRecord: ConsumerRecord<*, *>,
    ) {
        val counting =
            FailureReportingOperations(delegate = kafkaTemplate) { failure ->
                failures.increment()
                logger.error(failure) {
                    "Dead-letter publish to ${outRecord.topic()} failed; CDC record topic=${inRecord.topic()} " +
                        "partition=${inRecord.partition()} offset=${inRecord.offset()} is dropped (its outbox row " +
                        "stays PENDING for the recovery sweep)"
                }
            }
        super.publish(outRecord, counting, inRecord)
    }
}

private class FailureReportingOperations(
    private val delegate: KafkaOperations<Any, Any>,
    private val onFailure: (Throwable) -> Unit,
) : KafkaOperations<Any, Any> by delegate {
    override fun send(record: ProducerRecord<Any, Any>): CompletableFuture<SendResult<Any, Any>> =
        try {
            delegate.send(record).whenComplete { _, failure -> failure?.let(onFailure) }
        } catch (exception: Exception) {
            onFailure(exception)
            throw exception
        }
}

class CdcDeadLetterRecovery(
    jsonTemplate: KafkaTemplate<String, Any>?,
    meterRegistry: MeterRegistry,
    private val jsonProducerFactory: DefaultKafkaProducerFactory<Any, Any>? =
        jsonTemplate?.let { deadLetterProducerFactory(jsonTemplate = it) },
    private val bytesProducerFactory: DefaultKafkaProducerFactory<Any, Any>? =
        jsonTemplate?.let {
            deadLetterProducerFactory(
                jsonTemplate = it,
                valueSerializer = ByteArraySerializer::class.java,
            )
        },
) : DisposableBean {
    val recoverer: ConsumerRecordRecoverer =
        if (jsonProducerFactory == null || bytesProducerFactory == null) {
            val dropped = meterRegistry.counter(METRIC_DLT_PUBLISH_FAILURES)
            ConsumerRecordRecoverer { record, exception ->
                dropped.increment()
                logger.error(exception) {
                    "No KafkaTemplate for a dead-letter topic; dropping CDC record " +
                        "topic=${record.topic()} partition=${record.partition()} offset=${record.offset()}"
                }
            }
        } else {
            cdcDeadLetterRecoverer(
                jsonTemplate = KafkaTemplate(jsonProducerFactory),
                bytesTemplate = KafkaTemplate(bytesProducerFactory),
                meterRegistry = meterRegistry,
            )
        }

    override fun destroy() {
        jsonProducerFactory?.destroy()
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
        // On shutdown finish only the record in hand (its status write needs the DataSource still open); the rest
        // of the poll is uncommitted and redelivered to another pod, where the rows are still PENDING.
        containerProperties.isStopImmediate = true
        containerProperties.shutdownTimeout = CDC_LISTENER_SHUTDOWN_TIMEOUT.toMillis()
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
