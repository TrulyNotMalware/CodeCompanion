package dev.notypie.application.configurations.conditions

import org.springframework.context.annotation.Conditional

@Deprecated("This is deprecated.")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Conditional(OnStandAloneCondition::class)
annotation class OnStandAloneMode
