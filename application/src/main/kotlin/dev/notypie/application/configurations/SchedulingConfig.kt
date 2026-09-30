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
    // Declared by name so Boot's default backs off: with spring.threads.virtual.enabled it would register a
    // SimpleAsyncTaskScheduler, which runs every fixed-delay job on one thread and ignores the pool size.
    @Bean(name = ["taskScheduler"])
    fun taskScheduler(builder: ThreadPoolTaskSchedulerBuilder): ThreadPoolTaskScheduler = builder.build()
}
