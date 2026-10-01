package dev.notypie.application.configurations

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

// PoolingPublisherConfig also declares @EnableScheduling; Spring allows the duplicate — keep both.
@Configuration
@EnableScheduling
class SchedulingConfig {
    // Boot's virtual-thread scheduler ignores pool.size and runs every fixedDelay job on one thread.
    @Bean
    fun taskScheduler(threadPoolTaskSchedulerBuilder: ThreadPoolTaskSchedulerBuilder): ThreadPoolTaskScheduler =
        threadPoolTaskSchedulerBuilder.build()
}
