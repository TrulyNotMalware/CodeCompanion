package dev.notypie.application.configurations

import dev.notypie.application.configurations.conditions.OnApplicationEventPublisher
import dev.notypie.application.configurations.conditions.OnCdcConsumer
import dev.notypie.application.configurations.conditions.OnKafkaEventPublisher
import dev.notypie.application.configurations.conditions.OnPollingConsumer
import dev.notypie.application.service.relay.DebeziumLogTailingProcessor
import dev.notypie.application.service.relay.MessageProcessor
import dev.notypie.application.service.relay.MessageRelayService
import dev.notypie.application.service.relay.PollingMessageProcessor
import dev.notypie.application.service.relay.SlackMessageRelayServiceImpl
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.exception.ErrorBroadcaster
import dev.notypie.exception.StdoutErrorBroadcaster
import dev.notypie.impl.command.AppEventPublisher
import dev.notypie.impl.command.KafkaEventPublisher
import dev.notypie.repository.outbox.MessageOutboxRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

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
        clock: Clock,
    ) = PollingMessageProcessor(
        outboxRepository = outboxRepository,
        messageRelayService = messageRelayService,
        appConfig = appConfig,
        clock = clock,
    )
}

@Configuration
@Conditional(OnCdcConsumer::class)
class CdcPublisherConfig {
    @Bean
    fun debeziumLogTailingProcessor(
        outboxRepository: MessageOutboxRepository,
        messageRelayService: MessageRelayService,
        clock: Clock,
    ) = DebeziumLogTailingProcessor(
        outboxRepository = outboxRepository,
        relayService = messageRelayService,
        clock = clock,
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

@Configuration
class ErrorBroadcasterConfig {
    @Bean
    fun stdoutErrorBroadcaster(): ErrorBroadcaster = StdoutErrorBroadcaster()
}
