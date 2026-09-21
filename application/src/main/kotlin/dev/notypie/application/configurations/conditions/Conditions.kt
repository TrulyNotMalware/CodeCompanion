package dev.notypie.application.configurations.conditions

import dev.notypie.application.configurations.APP_CONFIG_PROPERTIES_PREFIX
import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.configurations.EventPublisherType
import dev.notypie.application.configurations.OutboxReaderStrategy
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata

fun Environment.extractAppConfig(): AppConfig =
    Binder.get(this).bind(APP_CONFIG_PROPERTIES_PREFIX, AppConfig::class.java).orElseGet {
        AppConfig()
    }

class OnPollingConsumer : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        context.environment
            .extractAppConfig()
            .mode.outboxReadingStrategy == OutboxReaderStrategy.POLLING
}

class OnCdcConsumer : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        context.environment
            .extractAppConfig()
            .mode.outboxReadingStrategy == OutboxReaderStrategy.CDC
}

class OnKafkaEventPublisher : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        context.environment
            .extractAppConfig()
            .mode.eventPublisher == EventPublisherType.KAFKA
}

class OnApplicationEventPublisher : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        context.environment
            .extractAppConfig()
            .mode.eventPublisher == EventPublisherType.APPLICATION_EVENT
}
