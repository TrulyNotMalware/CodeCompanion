package dev.notypie.configurations

import dev.notypie.impl.retry.RetryService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.resilience.annotation.EnableResilientMethods

@EnableResilientMethods
@Configuration
class RetryConfiguration {
    @Bean
    fun retryService(): RetryService = RetryService()
}

enum class RetryOptions(
    internal val default: Long,
) {
    MAX_ATTEMPTS(default = 3L),
    INITIAL_DELAY(default = 100L),
    MULTIPLIER(default = 2L),
    MAX_DELAY(default = 10000L),
    JITTER(default = 10L),
}
