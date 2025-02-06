package dev.notypie.application.configurations.conditions

import dev.notypie.application.configurations.AppConfig
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata

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
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val binder = Binder.get(context.environment)
        val appConfig = binder.bind("slack.app", AppConfig::class.java).orElse(AppConfig())
        return appConfig.mode.standAlone
    }
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
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val binder = Binder.get(context.environment)
        val appConfig = binder.bind("slack.app", AppConfig::class.java).orElse(AppConfig())
        return appConfig.mode.microService
    }
}