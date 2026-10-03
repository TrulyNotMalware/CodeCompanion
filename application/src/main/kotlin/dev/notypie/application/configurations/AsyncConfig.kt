package dev.notypie.application.configurations

import dev.notypie.application.service.relay.RELAY_RECORD_TIME_BOUND
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Primary
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor

// Destroy-time waits run one after another; k8s/deployment.yaml terminationGracePeriodSeconds must cover their sum.
const val DEFAULT_EXECUTOR_SHUTDOWN_AWAIT_SECONDS = 10

val RECORD_SHUTDOWN_WAIT: Duration = Duration.ofSeconds(Math.ceilDiv(RELAY_RECORD_TIME_BOUND.toMillis(), 1_000L))

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
            setAwaitTerminationSeconds(DEFAULT_EXECUTOR_SHUTDOWN_AWAIT_SECONDS)
            initialize()
        }

    @Bean(name = ["relayTaskExecutor"])
    @DependsOn("entityManagerFactory")
    fun relayTaskExecutor(appConfig: AppConfig): Executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 4
            maxPoolSize = 4
            queueCapacity = relayQueueCapacity(appConfig = appConfig)
            setThreadNamePrefix("relay-")
            setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(RECORD_SHUTDOWN_WAIT.seconds.toInt())
            initialize()
        }
}
