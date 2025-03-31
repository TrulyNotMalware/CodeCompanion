package dev.notypie.configurations

import dev.notypie.impl.retry.RetryService
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.retry.annotation.EnableRetry
import org.springframework.retry.backoff.FixedBackOffPolicy
import org.springframework.retry.policy.SimpleRetryPolicy
import org.springframework.retry.support.RetryTemplate

@EnableRetry
@Configuration
class RetryConfiguration {

    @Bean
    @ConditionalOnMissingBean(RetryTemplate::class)
    fun retryTemplate(): RetryTemplate{
        val fixedBackOffPolicy = FixedBackOffPolicy()
            .apply { backOffPeriod = 2000L }
        val retryPolicy = SimpleRetryPolicy(3, mapOf(Exception::class.java to true))
        return RetryTemplate()
            .apply {
                setBackOffPolicy(fixedBackOffPolicy)
                setRetryPolicy(retryPolicy)
            }
    }

    @Bean
    fun retryService(
        retryTemplate: RetryTemplate
    ): RetryService = RetryService(retryTemplate = retryTemplate)
}