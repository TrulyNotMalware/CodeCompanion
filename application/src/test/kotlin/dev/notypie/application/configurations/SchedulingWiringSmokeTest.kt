package dev.notypie.application.configurations

import dev.notypie.application.outbox.createOutboxRow
import dev.notypie.application.outbox.createRelayService
import dev.notypie.application.outbox.stubClaimLifecycle
import dev.notypie.application.service.relay.OutboxClaim
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.command.event.createPostEventPayloadContents
import dev.notypie.impl.command.event.successOutput
import dev.notypie.repository.outbox.MessageOutboxRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

                // Review F2/F4: the slots come from the configured capacity, so they are checked against the real bean.
                then(
                    "the relay hands out exactly the slots the real relay executor takes, and using them never rejects",
                ) {
                    runner.run { context ->
                        val relay = context.getBean("relayTaskExecutor").shouldBeInstanceOf<ThreadPoolTaskExecutor>()
                        relay.threadPoolExecutor.prestartAllCoreThreads()
                        val release = CountDownLatch(1)
                        val dispatched = AtomicInteger(0)
                        val messageDispatcher = mockk<MessageDispatcher>()
                        every { messageDispatcher.dispatch(event = any()) } answers {
                            release.await(5L, TimeUnit.SECONDS)
                            dispatched.incrementAndGet()
                            successOutput(
                                payload =
                                    createPostEventPayloadContents(
                                        commandDetailType = CommandDetailType.SIMPLE_TEXT,
                                    ),
                                commandType = CommandType.EXTERNAL_API,
                            )
                        }
                        val outboxRepository = mockk<MessageOutboxRepository>()
                        outboxRepository.stubClaimLifecycle()
                        val service =
                            createRelayService(
                                outboxRepository = outboxRepository,
                                messageDispatcher = messageDispatcher,
                                relayTaskExecutor = relay,
                                appConfig = context.getBean(AppConfig::class.java),
                            )

                        fun claims(count: Int) =
                            List(size = count) {
                                OutboxClaim(row = createOutboxRow(eventId = UUID.randomUUID().toString()), attempt = 1)
                            }

                        val queued = service.reserveDispatchSlots(wanted = Int.MAX_VALUE)
                        service.batchPendingMessages(claims = claims(count = queued))
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
                        while (relay.activeCount < relay.maxPoolSize && System.nanoTime() < deadline) Thread.sleep(1L)
                        val running = service.reserveDispatchSlots(wanted = Int.MAX_VALUE)
                        service.batchPendingMessages(claims = claims(count = running))
                        release.countDown()
                        while (dispatched.get() < queued + running && System.nanoTime() < deadline) Thread.sleep(1L)

                        queued shouldBe relay.queueCapacity
                        running shouldBe relay.maxPoolSize
                        dispatched.get() shouldBe relay.queueCapacity + relay.maxPoolSize
                    }
                }
            }
        }
    })
