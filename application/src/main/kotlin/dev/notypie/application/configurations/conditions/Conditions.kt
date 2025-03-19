package dev.notypie.application.configurations.conditions

import dev.notypie.application.configurations.AppConfig
import dev.notypie.application.configurations.PublisherType
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
class OnPoolingPublisher: Condition{

    /**
     * Evaluates whether the given condition matches based on the application configuration.
     *
     * @param context the condition context containing the current environment and other related configuration information
     * @param metadata metadata of the type to which the condition is applied
     * @return true if the configured publisher mode is `PublisherType.POOLING`; false otherwise
     */
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        context.environment.extractAppConfig().mode.publisher == PublisherType.POOLING

}

/**
 * Conditional class used to determine if the application is running
 * in a mode where the publisher type is CDC (Change Data Capture).
 * This condition checks the application's configuration to verify
 * if the publisher mode is set to `PublisherType.CDC`.
 */
class OnCdcPublisher: Condition{

    /**
     * Evaluates whether the condition is met based on the given context and metadata.
     * The condition checks if the application configuration specifies the publisher mode as `PublisherType.CDC`.
     *
     * @param context provides the condition context, including access to the environment and bean factory.
     * @param metadata metadata of the class or method being checked, including annotations.
     * @return `true` if the publisher mode is `PublisherType.CDC`, otherwise `false`.
     */
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        context.environment.extractAppConfig().mode.publisher == PublisherType.CDC
}