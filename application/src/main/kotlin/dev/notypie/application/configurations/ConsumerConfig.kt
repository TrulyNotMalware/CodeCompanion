package dev.notypie.application.configurations

import dev.notypie.application.configurations.conditions.OnApplicationEventPublisher
import dev.notypie.application.configurations.conditions.OnCdcConsumer
import dev.notypie.application.configurations.conditions.OnKafkaEventPublisher
import dev.notypie.application.configurations.conditions.OnPollingConsumer
import dev.notypie.application.service.relay.DebeziumLogTailingProcessor
import dev.notypie.application.service.relay.MessageProcessor
import dev.notypie.application.service.relay.OutboxPayloadRenderer
import dev.notypie.application.service.relay.PollingMessageProcessor
import dev.notypie.application.service.relay.SlackMessageRelayServiceImpl
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.exception.ErrorBroadcaster
import dev.notypie.exception.StdoutErrorBroadcaster
import dev.notypie.impl.command.AppEventPublisher
import dev.notypie.impl.command.KafkaEventPublisher
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.repository.outbox.MessageOutboxRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@Conditional(OnPollingConsumer::class)
@EnableScheduling
class PoolingPublisherConfig {
    @Bean
    @ConditionalOnMissingBean(MessageProcessor::class)
    fun poolingOutboxMessageProcessor(
        outboxRepository: MessageOutboxRepository,
        messageRelayService: SlackMessageRelayServiceImpl,
        appConfig: AppConfig,
    ) = PollingMessageProcessor(
        outboxRepository = outboxRepository,
        messageRelayService = messageRelayService,
        appConfig = appConfig,
    )
}

@Configuration
@Conditional(OnCdcConsumer::class)
class CdcPublisherConfig {
    @Bean
    fun debeziumLogTailingProcessor(
        applicationEventPublisher: ApplicationEventPublisher,
        messageDispatcher: MessageDispatcher,
        payloadRenderer: OutboxPayloadRenderer,
        outboxRepository: MessageOutboxRepository,
    ) = DebeziumLogTailingProcessor(
        messageDispatcher = messageDispatcher,
        payloadRenderer = payloadRenderer,
        eventPublisher = applicationEventPublisher,
        outboxRepository = outboxRepository,
    )
}

@Configuration
@Conditional(OnKafkaEventPublisher::class)
class KafkaEventPublisherConfig {
    @Bean
    fun eventPublisher(
        kafkaTemplate: KafkaTemplate<String, Any>,
        applicationEventPublisher: ApplicationEventPublisher,
    ): EventPublisher =
        KafkaEventPublisher(
            kafkaTemplate = kafkaTemplate,
            applicationEventPublisher = applicationEventPublisher,
        )
}

@Configuration
@Conditional(OnApplicationEventPublisher::class)
class ApplicationEventPublisherConfig {
    @Bean
    @ConditionalOnMissingBean(EventPublisher::class)
    fun eventPublisher(applicationEventPublisher: ApplicationEventPublisher): EventPublisher =
        AppEventPublisher(applicationEventPublisher = applicationEventPublisher)
}

// Mode-independent: the Kafka-backed broadcaster was a TODO() that would have thrown from an error path, so
// the log-only implementation is the only one until a real error topic exists.
@Configuration
class ErrorBroadcasterConfig {
    @Bean
    fun stdoutErrorBroadcaster(): ErrorBroadcaster = StdoutErrorBroadcaster()
}
