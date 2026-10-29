package dev.notypie.application.configurations

import dev.notypie.application.service.relay.RELAY_RECORD_TIME_BOUND
import io.github.oshai.kotlinlogging.KotlinLogging
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

const val DEFAULT_EXECUTOR_SHUTDOWN_AWAIT_SECONDS = 10

val RECORD_SHUTDOWN_WAIT: Duration = Duration.ofSeconds(Math.ceilDiv(RELAY_RECORD_TIME_BOUND.toMillis(), 1_000L))

fun relayQueueCapacity(appConfig: AppConfig): Int = appConfig.outbox.polling.batchSize

private val relayLog = KotlinLogging.logger {}

internal val RELAY_UNCAUGHT_ERROR_LOGGER: Thread.UncaughtExceptionHandler =
    Thread.UncaughtExceptionHandler { thread, failure ->
        relayLog.error(failure) {
            "Relay worker ${thread.name} ended on an uncaught ${failure::class.java.name}; its claim stays " +
                "IN_PROGRESS for the recovery sweep"
        }
    }

@Configuration
@EnableAsync
class AsyncConfig : AsyncConfigurer {
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
        object : ThreadPoolTaskExecutor() {
            override fun newThread(runnable: Runnable): Thread =
                super.newThread(runnable).apply { uncaughtExceptionHandler = RELAY_UNCAUGHT_ERROR_LOGGER }
        }.apply {
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
