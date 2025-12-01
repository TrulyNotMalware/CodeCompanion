package dev.notypie.impl.retry

import dev.notypie.configurations.RetryOptions
import org.springframework.core.retry.RetryException
import org.springframework.core.retry.RetryPolicy
import org.springframework.core.retry.RetryTemplate
import org.springframework.util.backoff.FixedBackOff
import java.time.Duration

class RetryService(
    private val retryTemplate: RetryTemplate,
) {
    fun <T> execute(
        action: () -> T,
        recoveryCallBack: (() -> T)? = null,
        maxAttempts: Long = RetryOptions.MAX_ATTEMPTS.default,
        initialDelay: Long = RetryOptions.INITIAL_DELAY.default,
        multiplier: Double = RetryOptions.MULTIPLIER.default.toDouble(),
        maxDelay: Long = RetryOptions.MAX_DELAY.default,
        jitter: Long = RetryOptions.JITTER.default,
        exceptions: List<Class<out Throwable>> = listOf(Exception::class.java),
    ): T {
        val policy =
            RetryPolicy
                .builder()
                .maxRetries(maxAttempts)
                .delay(Duration.ofMillis(initialDelay))
                .multiplier(multiplier)
                .maxDelay(Duration.ofMillis(maxDelay))
                .jitter(Duration.ofMillis(jitter))
                .includes(exceptions)
                .build()
        retryTemplate.retryPolicy = policy
        return try {
            retryTemplate.execute { action() }
        } catch (e: RetryException) {
            recoveryCallBack?.invoke() ?: throw e
        }
    }

    private fun createFixedBackOffPolicy(interval: Long) = FixedBackOff(interval)
}
