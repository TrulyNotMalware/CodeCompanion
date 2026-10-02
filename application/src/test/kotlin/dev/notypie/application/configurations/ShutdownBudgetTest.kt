package dev.notypie.application.configurations

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import jakarta.persistence.EntityManagerFactory
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.util.ReflectionTestUtils
import org.yaml.snakeyaml.Yaml
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

// Review T12/F1: a record still being dispatched at SIGTERM must finish and record its status before the context
// closes the DataSource, or another pod's sweep re-sends it. Each layer's timeout has to contain the one inside it, and
// the pod's grace has to contain the waits that run one after the other.
class ShutdownBudgetTest :
    BehaviorSpec({
        val oneRecord = CDC_LISTENER_SHUTDOWN_TIMEOUT

        given("the CDC listener container") {
            val factory =
                KafkaConsumerConfiguration(
                    convention = KafkaObservationConvention(),
                    kafkaProperties = KafkaProperties(),
                    kafkaTemplateProvider = mockk(relaxed = true),
                ).concurrentKafkaListenerContainerFactory(
                    consumerFactory = mockk(relaxed = true),
                    cdcDeadLetterRecovery =
                        CdcDeadLetterRecovery(
                            jsonTemplate = null,
                            meterRegistry = SimpleMeterRegistry(),
                        ),
                )

            then("it stops after the record in hand, not the rest of the poll, and waits one record for it") {
                factory.containerProperties.isStopImmediate shouldBe true
                factory.containerProperties.shutdownTimeout shouldBe oneRecord.toMillis()
                oneRecord shouldBeGreaterThanOrEqualTo Duration.ofSeconds(60L)
            }
        }

        given("the relay executor") {
            val executor =
                AsyncConfig().relayTaskExecutor(appConfig = AppConfig(), entityManagerFactory = mockk(relaxed = true))
                    as ThreadPoolTaskExecutor

            then("it lets a dispatch that was running at shutdown finish") {
                awaitMillis(executor) shouldBeGreaterThanOrEqual oneRecord.toMillis()
                executor.shutdown()
            }
        }

        // Review F1: nothing ordered the relay executor's destroy-time wait before the DataSource closed.
        given("the relay executor in a context that also holds the EntityManagerFactory") {
            val events = ConcurrentLinkedQueue<String>()
            val entityManagerFactory =
                mockk<EntityManagerFactory>(relaxed = true) { every { close() } answers { events.add("emf closed") } }
            val started = CountDownLatch(1)
            var dependents = emptyList<String>()

            `when`("the context closes while a dispatch is running") {
                ApplicationContextRunner()
                    .withBean("entityManagerFactory", EntityManagerFactory::class.java, { entityManagerFactory })
                    .withBean(AppConfig::class.java, { AppConfig() })
                    .withUserConfiguration(AsyncConfig::class.java)
                    .run { context ->
                        dependents = context.beanFactory.getDependentBeans("entityManagerFactory").toList()
                        context.getBean("relayTaskExecutor", Executor::class.java).execute {
                            started.countDown()
                            Thread.sleep(300L)
                            events.add("dispatch finished")
                        }
                        started.await(5L, TimeUnit.SECONDS)
                    }

                then("the executor depends on it, so the dispatch finishes before it (and the DataSource) closes") {
                    dependents shouldContain "relayTaskExecutor"
                    events.toList() shouldBe listOf("dispatch finished", "emf closed")
                }
            }
        }

        listOf("local", "dev", "prod").forEach { profile ->
            given("the $profile profile") {
                then("each lifecycle phase waits at least as long as the listener container's shutdown") {
                    shutdownPhase(profile = profile) shouldBeGreaterThanOrEqualTo oneRecord
                }
            }
        }

        given("the k8s Deployment") {
            @Suppress("UNCHECKED_CAST")
            val podSpec =
                ClassPathResource("k8s/deployment.yaml")
                    .inputStream
                    .use { stream -> Yaml().loadAll(stream).map { it as Map<String, Any> }.toList() }
                    .single { it["kind"] == "Deployment" }
                    .let { (it["spec"] as Map<String, Any>)["template"] as Map<String, Any> }
                    .let { it["spec"] as Map<String, Any> }

            // The waits run one after the other: preStop, then the lifecycle phases (the Kafka listener phase first),
            // then each executor's destroy-time wait as the context destroys it.
            then("the grace period covers preStop, the Kafka phase, both executors' shutdown waits and a margin") {
                @Suppress("UNCHECKED_CAST")
                val preStopCommand =
                    (podSpec["containers"] as List<Map<String, Any>>)
                        .single()
                        .let { (it["lifecycle"] as Map<String, Any>)["preStop"] as Map<String, Any> }
                        .let { (it["exec"] as Map<String, Any>)["command"] as List<String> }
                val preStopSeconds = Regex("""sleep (\d+)""").find(preStopCommand.last())!!.groupValues[1].toLong()
                val grace = (podSpec["terminationGracePeriodSeconds"] as Number).toLong()
                val asyncConfig = AsyncConfig()
                val relay =
                    asyncConfig.relayTaskExecutor(appConfig = AppConfig(), entityManagerFactory = mockk(relaxed = true))
                        as ThreadPoolTaskExecutor
                val async = asyncConfig.getAsyncExecutor() as ThreadPoolTaskExecutor
                val serialWaitSeconds =
                    preStopSeconds +
                        shutdownPhase(profile = "prod").seconds +
                        awaitMillis(relay) / 1_000L +
                        awaitMillis(async) / 1_000L
                relay.shutdown()
                async.shutdown()

                grace shouldBeGreaterThanOrEqual serialWaitSeconds + 15L
            }
        }
    })

private fun awaitMillis(executor: ThreadPoolTaskExecutor): Long =
    ReflectionTestUtils.getField(executor, "awaitTerminationMillis") as Long

private fun shutdownPhase(profile: String): Duration {
    var phase = Duration.ZERO
    ApplicationContextRunner()
        .withInitializer(ConfigDataApplicationContextInitializer())
        .withPropertyValues("spring.profiles.active=$profile")
        .run { context ->
            phase =
                Binder
                    .get(context.environment)
                    .bind("spring.lifecycle.timeout-per-shutdown-phase", Duration::class.java)
                    .orElse(Duration.ofSeconds(30L))!!
        }
    return phase
}
