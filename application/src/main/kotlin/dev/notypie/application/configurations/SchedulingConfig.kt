package dev.notypie.application.configurations

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

// The only @EnableScheduling in CDC mode: PoolingPublisherConfig's copy is active in polling mode only.
@Configuration
@EnableScheduling
class SchedulingConfig {
    @Bean
    fun taskScheduler(threadPoolTaskSchedulerBuilder: ThreadPoolTaskSchedulerBuilder): ThreadPoolTaskScheduler =
        threadPoolTaskSchedulerBuilder.build()
}
