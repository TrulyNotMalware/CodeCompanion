package dev.notypie.impl.retry

import org.springframework.retry.backoff.ExponentialBackOffPolicy
import org.springframework.retry.policy.SimpleRetryPolicy
import org.springframework.retry.support.RetryTemplate

class RetryService(
    private val retryTemplate: RetryTemplate
) {
    fun <T> execute(
        action: () -> T,
        recoveryCallBack: (() -> T)? = null,
        maxAttempts: Int = 3,
        exceptions: List<Class<out Throwable>> = listOf(Exception::class.java),
        interval: Long = 2000L
    ): T = this.retryTemplate.apply {
        setBackOffPolicy(createExponentialBackOffPolicy(interval))
        setRetryPolicy(createRetryPolicy(maxAttempts, exceptions))
    }.let {
        if (recoveryCallBack != null) {
            it.execute<T, Throwable>(
                { action() },
                { recoveryCallBack() }
            )
        } else {
            it.execute<T, Throwable> { action() }
        }
    }

    private fun configureRetryTemplate(
        maxAttempts: Int,
        exceptions: List<Class<out Throwable>>,
        interval: Long
    ): RetryTemplate = this.retryTemplate.apply {
        setBackOffPolicy(createExponentialBackOffPolicy(interval))
        setRetryPolicy(createRetryPolicy(maxAttempts, exceptions))
    }

    private fun createExponentialBackOffPolicy(interval: Long) =
        ExponentialBackOffPolicy().apply {
            initialInterval = 300L
            maxInterval = interval
            multiplier = 2.0
        }

    private fun createRetryPolicy(
        maxAttempts: Int,
        exceptions: List<Class<out Throwable>>
    ) = SimpleRetryPolicy(maxAttempts, exceptions.associateWith { true })

}