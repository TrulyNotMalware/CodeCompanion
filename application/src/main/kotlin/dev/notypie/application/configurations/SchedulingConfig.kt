package dev.notypie.application.configurations

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration
@EnableScheduling
class SchedulingConfig {
    @Bean
    fun taskScheduler(threadPoolTaskSchedulerBuilder: ThreadPoolTaskSchedulerBuilder): ThreadPoolTaskScheduler =
        threadPoolTaskSchedulerBuilder.build()
}
