package dev.notypie.application.configurations

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

// The real application.yaml plus the virtual-thread switch every profile turns on: the combination under which
// Boot 4.1.1 silently picked a single-threaded SimpleAsyncTaskScheduler for every fixed-delay job (review T1).
class SchedulingWiringSmokeTest :
    BehaviorSpec({
        val runner =
            ApplicationContextRunner()
                .withInitializer(ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration::class.java))
                .withUserConfiguration(SchedulingConfig::class.java, AsyncConfig::class.java)
                .withBean(AppConfig::class.java, { AppConfig() })
                .withPropertyValues("spring.threads.virtual.enabled=true")

        given("the scheduling and async wiring with virtual threads enabled") {
            `when`("the context starts") {
                then("the taskScheduler bean is a thread pool with room for more than one job at a time") {
                    runner.run { context ->
                        val scheduler = context.getBean("taskScheduler").shouldBeInstanceOf<ThreadPoolTaskScheduler>()
                        scheduler.scheduledThreadPoolExecutor.corePoolSize shouldBeGreaterThanOrEqual 2
                    }
                }

                then("a blocked fixed-delay job does not stop another fixed-delay job from running") {
                    runner.run { context ->
                        val scheduler = context.getBean("taskScheduler", TaskScheduler::class.java)
                        val release = CountDownLatch(1)
                        val otherRan = CountDownLatch(1)
                        try {
                            scheduler.scheduleWithFixedDelay({ release.await() }, Duration.ofMillis(50L))
                            Thread.sleep(100L)
                            scheduler.scheduleWithFixedDelay({ otherRan.countDown() }, Duration.ofMillis(50L))

                            otherRan.await(2L, TimeUnit.SECONDS) shouldBe true
                        } finally {
                            release.countDown()
                        }
                    }
                }

                then("the relay executor rejects overflow instead of running it on the submitting scheduler thread") {
                    runner.run { context ->
                        val relay = context.getBean("relayTaskExecutor").shouldBeInstanceOf<ThreadPoolTaskExecutor>()
                        relay.threadPoolExecutor.rejectedExecutionHandler
                            .shouldBeInstanceOf<ThreadPoolExecutor.AbortPolicy>()
                        relay.queueCapacity shouldBe AppConfig().outbox.polling.batchSize
                    }
                }
            }
        }
    })
