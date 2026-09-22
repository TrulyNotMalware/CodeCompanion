package dev.notypie.impl.retry

import dev.notypie.configurations.RetryOptions
import org.springframework.core.retry.RetryException
import org.springframework.core.retry.RetryPolicy
import org.springframework.core.retry.RetryTemplate
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class RetryService {
    // RetryTemplate.retryPolicy is mutable shared state, so one template per distinct policy: callers on
    // different threads (relay executor, schedulers, request threads) must never see each other's settings.
    private val templates = ConcurrentHashMap<PolicyKey, RetryTemplate>()

    private data class PolicyKey(
        val maxAttempts: Long,
        val initialDelay: Long,
        val multiplier: Double,
        val maxDelay: Long,
        val jitter: Long,
        val exceptions: Set<Class<out Throwable>>,
    )

    // maxAttempts counts executions in total; Spring's maxRetries counts only the re-executions after the first.
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
        val key =
            PolicyKey(
                maxAttempts = maxAttempts,
                initialDelay = initialDelay,
                multiplier = multiplier,
                maxDelay = maxDelay,
                jitter = jitter,
                exceptions = exceptions.toSet(),
            )
        val template = templates.computeIfAbsent(key) { RetryTemplate().apply { retryPolicy = buildPolicy(it) } }
        return try {
            template.execute { action() }
        } catch (e: RetryException) {
            recoveryCallBack?.invoke() ?: throw e
        }
    }

    private fun buildPolicy(key: PolicyKey): RetryPolicy =
        RetryPolicy
            .builder()
            .maxRetries((key.maxAttempts - 1L).coerceAtLeast(0L))
            .delay(Duration.ofMillis(key.initialDelay))
            .multiplier(key.multiplier)
            .maxDelay(Duration.ofMillis(key.maxDelay))
            .jitter(Duration.ofMillis(key.jitter))
            .includes(key.exceptions.toList())
            .build()
}
