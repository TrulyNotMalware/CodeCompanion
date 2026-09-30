package dev.notypie.application.configurations

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.util.ReflectionTestUtils
import org.yaml.snakeyaml.Yaml
import java.time.Duration

// Review T12: a record still being dispatched at SIGTERM must finish and record its status before the context
// closes the DataSource, or another pod's sweep re-sends it. Each layer's timeout has to contain the one inside it.
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
            val executor = AsyncConfig().relayTaskExecutor(appConfig = AppConfig()) as ThreadPoolTaskExecutor

            then("it lets a dispatch that was running at shutdown finish") {
                (ReflectionTestUtils.getField(executor, "awaitTerminationMillis") as Long) shouldBeGreaterThanOrEqual
                    oneRecord.toMillis()
                executor.shutdown()
            }
        }

        listOf("local", "dev", "prod").forEach { profile ->
            given("the $profile profile") {
                then("each lifecycle phase waits at least as long as the listener container's shutdown") {
                    ApplicationContextRunner()
                        .withInitializer(ConfigDataApplicationContextInitializer())
                        .withPropertyValues("spring.profiles.active=$profile")
                        .run { context ->
                            val phase =
                                Binder
                                    .get(context.environment)
                                    .bind("spring.lifecycle.timeout-per-shutdown-phase", Duration::class.java)
                                    .orElse(Duration.ofSeconds(30L))!!
                            phase shouldBeGreaterThanOrEqualTo oneRecord
                        }
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

            then("the grace period covers preStop, one listener record and a margin for the rest of the close") {
                @Suppress("UNCHECKED_CAST")
                val preStopCommand =
                    (podSpec["containers"] as List<Map<String, Any>>)
                        .single()
                        .let { (it["lifecycle"] as Map<String, Any>)["preStop"] as Map<String, Any> }
                        .let { (it["exec"] as Map<String, Any>)["command"] as List<String> }
                val preStopSeconds = Regex("""sleep (\d+)""").find(preStopCommand.last())!!.groupValues[1].toLong()
                val grace = (podSpec["terminationGracePeriodSeconds"] as Number).toLong()

                grace shouldBeGreaterThanOrEqual preStopSeconds + oneRecord.seconds + 15L
            }
        }
    })
