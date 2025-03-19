package dev.notypie.application.configurations

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.ApplicationEventMulticaster
import org.springframework.context.event.SimpleApplicationEventMulticaster
import org.springframework.context.support.AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
@EnableAsync
class AsyncConfig : AsyncConfigurer{

    @Bean(name = [ APPLICATION_EVENT_MULTICASTER_BEAN_NAME ])
    fun applicationEventMulticaster(): ApplicationEventMulticaster{
        val eventMultiCaster = SimpleApplicationEventMulticaster()
        eventMultiCaster.setTaskExecutor(getAsyncExecutor())
        return eventMultiCaster
    }

    @Bean(name = ["threadPoolTaskExecutor"])
    override fun getAsyncExecutor(): Executor{
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 10
        executor.maxPoolSize = 10
        executor.setQueueCapacity(10000)
        executor.setThreadNamePrefix("AsyncThread-")
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(10)
        executor.initialize()
        return executor
    }
}