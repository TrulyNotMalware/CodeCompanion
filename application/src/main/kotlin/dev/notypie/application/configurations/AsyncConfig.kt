package dev.notypie.application.configurations

import jakarta.persistence.EntityManagerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

// The relay pool's queue, which is also how many dispatch slots SlackMessageRelayServiceImpl hands out: a claim is
// reserved a slot before it is claimed and gives it back when a pool thread starts it, so the queue cannot overflow.
fun relayQueueCapacity(appConfig: AppConfig): Int = appConfig.outbox.polling.batchSize

// No async multicaster: it would detach @TransactionalEventListener(BEFORE_COMMIT) from the tx.
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
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(10)
            initialize()
        }

    // Overflow is rejected, never run by the caller: the callers are scheduler threads shared by every @Scheduled job.
    // entityManagerFactory is never read: taking it makes this executor a dependent of the EntityManagerFactory, and
    // through it of the DataSource, so the context destroys this executor (waiting for its running dispatches) before
    // it closes either one, whatever order the beans were created in (review F1).
    @Bean(name = ["relayTaskExecutor"])
    fun relayTaskExecutor(
        appConfig: AppConfig,
        @Suppress("UNUSED_PARAMETER") entityManagerFactory: EntityManagerFactory,
    ): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 4
            maxPoolSize = 4
            queueCapacity = relayQueueCapacity(appConfig = appConfig)
            setThreadNamePrefix("relay-")
            setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
            setWaitForTasksToCompleteOnShutdown(true)
            // A dispatch running at shutdown gets one record's budget to record its status before the DataSource
            // closes. Queued claims are not waited for: SlackMessageRelayServiceImpl.stop() drains them first. This wait
            // starts only when the context destroys the bean, after every lifecycle phase, so k8s/deployment.yaml's
            // grace adds it to the Kafka phase instead of overlapping them (ShutdownBudgetTest checks the sum).
            setAwaitTerminationSeconds(CDC_LISTENER_SHUTDOWN_TIMEOUT.seconds.toInt())
            initialize()
        }
}
