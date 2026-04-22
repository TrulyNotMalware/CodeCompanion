package dev.notypie.application.configurations

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

/**
 * Spring ApplicationEventMulticaster is intentionally NOT overridden here with a TaskExecutor.
 *
 * An async multicaster would dispatch `@EventListener` callbacks on a pool thread, which
 * detaches them from the publishing thread's transaction context. That breaks
 * `@TransactionalEventListener(phase = BEFORE_COMMIT)` — the listener's
 * `TransactionSynchronization` must be registered on the publishing thread's active
 * transaction for the Transactional Outbox pattern to commit atomically with domain writes.
 *
 * Listeners that must not block the HTTP request thread (e.g. outbound network calls) opt
 * into async execution explicitly via `@Async`, which goes through the `threadPoolTaskExecutor`
 * bean defined below rather than the event multicaster.
 */
@Configuration
@EnableAsync
class AsyncConfig : AsyncConfigurer { // TODO REPLACE COROUTINE

    @Bean(name = ["threadPoolTaskExecutor"])
    @Primary
    override fun getAsyncExecutor(): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 10
            maxPoolSize = 10
            queueCapacity = 10000
//            threadNamePrefix = "AsyncThread-" val cannot be reassigned issue.
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
            initialize()
        }
}
