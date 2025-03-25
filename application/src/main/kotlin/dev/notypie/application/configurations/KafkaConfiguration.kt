package dev.notypie.application.configurations

import dev.notypie.application.configurations.conditions.OnCdcPublisher
import io.micrometer.common.KeyValues
import org.apache.kafka.clients.producer.ProducerConfig
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.*
import org.springframework.kafka.support.micrometer.KafkaListenerObservation
import org.springframework.kafka.support.micrometer.KafkaListenerObservationConvention
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext

@Configuration
@EnableKafka
@Conditional(OnCdcPublisher::class)
class KafkaConfiguration(
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
        }

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

@Configuration
@Conditional(OnCdcPublisher::class)
class KafkaObservationConvention: KafkaListenerObservationConvention{

    override fun getName(): String = "code.companion.listener"
    override fun getContextualName(context: KafkaRecordReceiverContext) = context.source + " receive"
    override fun getLowCardinalityKeyValues(context: KafkaRecordReceiverContext): KeyValues =
        KeyValues.of(
            KafkaListenerObservation.ListenerLowCardinalityTags.LISTENER_ID.asString(),
            context.listenerId
        )

}