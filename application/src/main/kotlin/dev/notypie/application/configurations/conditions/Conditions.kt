package dev.notypie.application.configurations.conditions

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.configurations.EventPublisherType
import dev.notypie.application.configurations.OutboxReaderStrategy
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata


fun Environment.extractAppConfig(): AppConfig
= Binder.get(this).bind("slack.app", AppConfig::class.java).orElse(AppConfig())


/**
 * A condition that determines whether the application is running in StandAlone mode.
 *
 * This condition uses the configuration property `slack.app.mode.standAlone`
 * to evaluate its outcome. If the property is set to `true`, the condition matches.
 *
 * The condition is typically used with the `@Conditional` annotation to apply
 * Spring beans or configurations only when the application is configured
 * to run in StandAlone mode.
 */
class OnStandAloneCondition: Condition {

    /**
     * Evaluates whether the current application mode is set to standalone.
     * This method checks the configuration properties under "slack.app"
     * to determine if the application is running in standalone mode.
     *
     * @param context the condition context, providing access to the environment and other resources
     * @param metadata metadata of the @Conditional annotation for the current component
     * @return true if the application mode is set to standalone, false otherwise
     */
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        context.environment.extractAppConfig().mode.standAlone
}

/**
 * A condition that determines whether the application is running in MicroService mode.
 *
 * This condition uses the configuration property `slack.app.mode.microService`
 * to evaluate its outcome. If the property is set to `true`, the condition matches.
 *
 * The condition is typically used with the `@Conditional` annotation to apply
 * Spring beans or configurations only when the application is configured
 * to run in MicroService mode.
 */
class OnMicroServiceCondition: Condition{
    /**
     * Evaluates whether the current application mode is set to microservice.
     * This method checks the configuration properties under "slack.app"
     * to determine if the application is running in microservice mode.
     *
     * @param context the condition context, providing access to the environment and other resources
     * @param metadata metadata of the {@code @Conditional} annotation for the current component
     * @return true if the application mode is set to microservice, false otherwise
     */
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        !context.environment.extractAppConfig().mode.standAlone

}

/**
 * A custom Spring `Condition` implementation that conditionally evaluates the configuration
 * based on whether the application is configured to use the `POOLING` publisher mode.
 *
 * The condition checks the application's configuration properties defined under the "slack.app" prefix.
 * If the `publisher` mode in the configuration is set to `PublisherType.POOLING`, the condition evaluates as true.
 *
 * Typically used with the `@Conditional` annotation to conditionally load application components
 * or beans defined under specific configuration setups.
 */
class OnPollingConsumer: Condition{

    /**
     * Evaluates whether the given condition matches based on the application configuration.
     *
     * @param context the condition context containing the current environment and other related configuration information
     * @param metadata metadata of the type to which the condition is applied
     * @return true if the configured publisher mode is `PublisherType.POOLING`; false otherwise
     */
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        context.environment.extractAppConfig().mode.outboxReadingStrategy == OutboxReaderStrategy.POLLING

}

/**
 * Conditional class used to determine if the application is running
 * in a mode where the publisher type is CDC (Change Data Capture).
 * This condition checks the application's configuration to verify
 * if the publisher mode is set to `PublisherType.CDC`.
 */
class OnCdcConsumer: Condition{

    /**
     * Evaluates whether the condition is met based on the given context and metadata.
     * The condition checks if the application configuration specifies the publisher mode as `PublisherType.CDC`.
     *
     * @param context provides the condition context, including access to the environment and bean factory.
     * @param metadata metadata of the class or method being checked, including annotations.
     * @return `true` if the publisher mode is `PublisherType.CDC`, otherwise `false`.
     */
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        context.environment.extractAppConfig().mode.outboxReadingStrategy == OutboxReaderStrategy.CDC
}

/**
 * Condition implementation used to determine whether the application should configure Kafka
 * as the event publisher. The condition evaluates based on the application's configuration
 * properties and checks whether the `eventPublisher` mode in the app configuration is set
 * to `KAFKA`.
 *
 * This is useful for selectively enabling or configuring Kafka-related beans or features
 * based on the application's current operational mode.
 */
class OnKafkaEventPublisher: Condition{

    /**
     * Evaluates whether the current condition matches based on the application configuration's eventPublisher mode.
     *
     * @param context the condition context providing access to environment, bean definitions, and more
     * @param metadata metadata of the {@code @Configuration} or {@code @Bean} being checked
     * @return true if the eventPublisher mode is set to KAFKA, false otherwise
     */
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        context.environment.extractAppConfig().mode.eventPublisher == EventPublisherType.KAFKA
}

/**
 * Condition implementation that determines whether the application's event publisher mode
 * is configured to use `APPLICATION_EVENT`. This condition evaluates the application environment
 * configuration and matches when the `eventPublisher` property in the application's mode configuration
 * has the value `APPLICATION_EVENT`.
 *
 * This can be used as a condition for defining specific beans or configurations that depend on the
 * application using the Spring event-driven mechanism for publishing events.
 */
class OnApplicationEventPublisher: Condition{

    /**
     * Determines if the condition is met based on the application's configuration.
     *
     * The condition checks if the event publisher mode configured in the application
     * matches `EventPublisherType.APPLICATION_EVENT`.
     *
     * @param context the condition context used to evaluate the condition; provides
     *                access to the application environment and registry.
     * @param metadata metadata of the {@code @Conditional} annotation; provides
     *                 additional information about the condition's context.
     * @return `true` if the condition is met; otherwise, `false`.
     */
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        context.environment.extractAppConfig().mode.eventPublisher == EventPublisherType.APPLICATION_EVENT
}