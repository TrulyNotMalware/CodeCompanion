package dev.notypie.application.configurations

import dev.notypie.application.configurations.conditions.OnCdcConsumer
import dev.notypie.application.configurations.conditions.OnPollingConsumer
import dev.notypie.application.service.relay.DebeziumLogTailingProcessor
import dev.notypie.application.service.relay.MessageProcessor
import dev.notypie.application.service.relay.PoolingMessageProcessor
import dev.notypie.application.service.relay.SlackMessageRelayServiceImpl
import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.repository.outbox.MessageOutboxRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@Conditional(OnPollingConsumer::class)
@EnableScheduling
class PoolingPublisherConfig{

    @Bean
    @ConditionalOnMissingBean(MessageProcessor::class)
    fun poolingOutboxMessageProcessor(
        outboxRepository: MessageOutboxRepository,
        messageRelayService: SlackMessageRelayServiceImpl
    )
    = PoolingMessageProcessor(
        outboxRepository = outboxRepository, messageRelayService = messageRelayService
    )
}

@Configuration
@Conditional(OnCdcConsumer::class)
class CdcPublisherConfig{

    @Bean
    fun debeziumLogTailingProcessor(
        applicationEventPublisher: ApplicationEventPublisher,
        messageDispatcher: MessageDispatcher
    ) = DebeziumLogTailingProcessor(
        messageDispatcher = messageDispatcher,
        eventPublisher = applicationEventPublisher
    )
}