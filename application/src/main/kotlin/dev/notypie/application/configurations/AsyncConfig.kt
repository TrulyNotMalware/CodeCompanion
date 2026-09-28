package dev.notypie.application.configurations

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

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

    @Bean(name = ["relayTaskExecutor"])
    fun relayTaskExecutor(appConfig: AppConfig): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 4
            maxPoolSize = 4
            queueCapacity = appConfig.outbox.polling.batchSize
            setThreadNamePrefix("relay-")
            setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(30)
            initialize()
        }
}
