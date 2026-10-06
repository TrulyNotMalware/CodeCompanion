package dev.notypie.application.configurations

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

class SchedulingConfigTest :
    BehaviorSpec({
        val contextRunner =
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration::class.java))
                .withUserConfiguration(SchedulingConfig::class.java)

        given("virtual threads enabled with a scheduling pool size of 4") {
            `when`("the context starts") {
                then("@Scheduled jobs run on a ThreadPoolTaskScheduler with 4 threads") {
                    contextRunner
                        .withPropertyValues(
                            "spring.threads.virtual.enabled=true",
                            "spring.task.scheduling.pool.size=4",
                        ).run { context ->
                            val scheduler = context.getBean(TaskScheduler::class.java)
                            scheduler.shouldBeInstanceOf<ThreadPoolTaskScheduler>()
                            scheduler.scheduledThreadPoolExecutor.corePoolSize shouldBe 4
                        }
                }
            }
        }
    })
