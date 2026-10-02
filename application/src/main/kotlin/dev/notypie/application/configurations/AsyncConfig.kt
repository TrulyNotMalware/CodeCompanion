package dev.notypie.application.configurations

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
    @Bean(name = ["relayTaskExecutor"])
    fun relayTaskExecutor(appConfig: AppConfig): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 4
            maxPoolSize = 4
            queueCapacity = relayQueueCapacity(appConfig = appConfig)
            setThreadNamePrefix("relay-")
            setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
            setWaitForTasksToCompleteOnShutdown(true)
            // A dispatch running at shutdown gets one record's budget to record its status before the DataSource closes.
            setAwaitTerminationSeconds(CDC_LISTENER_SHUTDOWN_TIMEOUT.seconds.toInt())
            initialize()
        }
}
