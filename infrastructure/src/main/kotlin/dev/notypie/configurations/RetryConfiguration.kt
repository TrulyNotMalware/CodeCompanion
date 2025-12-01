package dev.notypie.configurations

import dev.notypie.impl.retry.RetryService
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.retry.RetryPolicy
import org.springframework.core.retry.RetryTemplate
import org.springframework.resilience.annotation.EnableResilientMethods
import java.time.Duration

@EnableResilientMethods
@Configuration
class RetryConfiguration {
    @Bean
    @ConditionalOnMissingBean(RetryTemplate::class)
    fun retryTemplate(): RetryTemplate {
        val exceptions = listOf(Exception::class.java)
        val policy =
            RetryPolicy
                .builder()
                .maxRetries(RetryOptions.MAX_ATTEMPTS.default)
                .delay(Duration.ofMillis(RetryOptions.INITIAL_DELAY.default))
                .multiplier(RetryOptions.MULTIPLIER.default.toDouble())
                .maxDelay(Duration.ofMillis(RetryOptions.MAX_DELAY.default))
                .jitter(Duration.ofMillis(RetryOptions.JITTER.default))
                .includes(exceptions)
                .build()
        return RetryTemplate()
            .apply {
                retryPolicy = policy
            }
    }

    @Bean
    fun retryService(retryTemplate: RetryTemplate): RetryService = RetryService(retryTemplate = retryTemplate)
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
