package dev.notypie.application.configurations

import dev.notypie.application.configurations.conditions.OnCdcConsumer
import dev.notypie.application.configurations.conditions.OnKafkaEventPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.common.KeyValues
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerConfig
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.*
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.kafka.support.micrometer.KafkaListenerObservation
import org.springframework.kafka.support.micrometer.KafkaListenerObservationConvention
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext
import java.lang.Exception

private val logger = KotlinLogging.logger {  }

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
    KafkaConsumerConfiguration::class
    )
class KafkaEventPublisherConfiguration

class KafkaConsumerConfiguration(
    private val convention: KafkaObservationConvention,
    private val kafkaProperties: KafkaProperties
) {

    @Bean
    @ConditionalOnMissingBean(ConsumerFactory::class)
    fun consumerFactory(): ConsumerFactory<String, Any> =
        DefaultKafkaConsumerFactory(this.kafkaProperties.buildConsumerProperties())


    @Bean
    @ConditionalOnMissingBean(ConcurrentKafkaListenerContainerFactory::class)
    fun concurrentKafkaListenerContainerFactory(kafkaProperties: KafkaProperties) =
        ConcurrentKafkaListenerContainerFactory<String, Any>().apply {
            consumerFactory = consumerFactory()
            containerProperties.isObservationEnabled = true
            containerProperties.isMicrometerEnabled = false
            containerProperties.observationConvention = convention
            setCommonErrorHandler(KafkaErrorHandler())
        }

}

class KafkaProducerConfiguration(
    private val kafkaProperties: KafkaProperties
){
    @Bean
    @ConditionalOnMissingBean(ProducerFactory::class)
    fun producerFactory(): ProducerFactory<String, Any> =
        DefaultKafkaProducerFactory(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to this.kafkaProperties.bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to this.kafkaProperties.producer.keySerializer,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to this.kafkaProperties.producer.valueSerializer,
            )
        )

    @Bean
    @ConditionalOnMissingBean(KafkaTemplate::class)
    fun kafkaTemplate(): KafkaTemplate<String, Any> =
        KafkaTemplate(producerFactory()).apply {
            setObservationEnabled(true)
            setMicrometerEnabled(false)
        }
}

class KafkaObservationConvention: KafkaListenerObservationConvention{

    override fun getName(): String = "code.companion.listener"
    override fun getContextualName(context: KafkaRecordReceiverContext) = context.source + " receive"
    override fun getLowCardinalityKeyValues(context: KafkaRecordReceiverContext): KeyValues =
        KeyValues.of(
            KafkaListenerObservation.ListenerLowCardinalityTags.LISTENER_ID.asString(),
            context.listenerId
        )

}

class KafkaErrorHandler: CommonErrorHandler{

    override fun handleOtherException(
        thrownException: Exception,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer,
        batchListener: Boolean
    ) {
        super.handleOtherException(thrownException, consumer, container, batchListener)
    }

    override fun handleOne(
        thrownException: Exception,
        record: ConsumerRecord<*, *>,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer
    ): Boolean {
        if(record.value() == null || record.value().toString().isBlank()){
            logger.warn { "Received null or blank message. topic: ${record.topic()}, partition: ${record.partition()}, offset: ${record.offset()}" }
            return true
        }
        return super.handleOne(thrownException, record, consumer, container)
    }

    override fun seeksAfterHandling(): Boolean {
        return super.seeksAfterHandling()
    }
}