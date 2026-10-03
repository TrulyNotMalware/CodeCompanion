package dev.notypie.application.configurations

import dev.notypie.application.outbox.createRelayService
import dev.notypie.application.service.agent.AgentTurn
import dev.notypie.application.service.relay.RELAY_RECORD_TIME_BOUND
import dev.notypie.impl.command.RestClientRequester
import dev.notypie.impl.command.SLACK_DISPATCH_TIME_BOUND
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.autoconfigure.context.LifecycleProperties
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.Lifecycle
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.io.ClassPathResource
import org.springframework.kafka.config.KafkaListenerConfigUtils
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.listener.AbstractMessageListenerContainer
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.util.ReflectionTestUtils
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

class ShutdownBudgetTest :
    BehaviorSpec({
        @Suppress("UNCHECKED_CAST")
        fun Any?.at(key: String): Any? = (this as Map<String, Any?>)[key]

        given("the record budget derived from the timeouts in code") {
            then("it is the profile lookup, two Slack retry runs around one inline wait, and the status backoff") {
                SLACK_DISPATCH_TIME_BOUND shouldBe Duration.ofMillis(39_660L)
                RELAY_RECORD_TIME_BOUND shouldBe RestClientRequester.DEFAULT_READ_TIMEOUT.plusMillis(39_990L)
            }
        }

        given("the CDC listener container and the lifecycle processor of CDC mode") {
            val factory =
                KafkaConsumerConfiguration(
                    convention = KafkaObservationConvention(),
                    kafkaProperties = KafkaProperties(),
                ).concurrentKafkaListenerContainerFactory(
                    consumerFactory = mockk(relaxed = true),
                    cdcDeadLetterRecovery = mockk(relaxed = true),
                )
            val phase = Duration.ofSeconds(10L)
            val processor =
                CdcConsumerConfiguration().lifecycleProcessor(
                    lifecycleProperties =
                        mockk {
                            every { getIfAvailable(any()) } returns
                                LifecycleProperties().apply { timeoutPerShutdownPhase = phase }
                        },
                )

            fun timeoutOf(phase: Int): Long =
                ReflectionTestUtils.invokeMethod<Long>(processor, "determineShutdownTimeout", phase)!!

            then("the listener stops after the record in hand and its phase alone waits one record") {
                factory.containerProperties.isStopImmediate shouldBe true
                factory.containerProperties.shutdownTimeout shouldBe RECORD_SHUTDOWN_WAIT.toMillis()
                timeoutOf(phase = AbstractMessageListenerContainer.DEFAULT_PHASE) shouldBe
                    RECORD_SHUTDOWN_WAIT.toMillis()
                timeoutOf(phase = SmartLifecycle.DEFAULT_PHASE) shouldBe phase.toMillis()
            }
        }

        given("the production profile and the Kubernetes Deployment") {
            val prod = YamlPropertySourceLoader().load("prod", ClassPathResource("application-prod.yaml")).single()
            val deployment =
                ClassPathResource("k8s/deployment.yaml").inputStream.use { stream ->
                    Yaml().loadAll(stream).toList().single { it.at("kind") == "Deployment" }
                }
            val podSpec = deployment.at("spec").at("template").at("spec")

            `when`("the worst-case shutdown is added up step by step") {
                val graceSeconds = podSpec.at("terminationGracePeriodSeconds") as Int

                @Suppress("UNCHECKED_CAST")
                val container = (podSpec.at("containers") as List<Any?>).single()

                @Suppress("UNCHECKED_CAST")
                val preStopCommand =
                    container
                        .at("lifecycle")
                        .at("preStop")
                        .at("exec")
                        .at("command") as List<String>
                val preStopSeconds = Regex("""sleep (\d+)""").find(preStopCommand.last())!!.groupValues[1].toInt()
                val phaseSeconds =
                    Duration
                        .parse("PT" + prod.getProperty("spring.lifecycle.timeout-per-shutdown-phase").toString())
                        .seconds
                        .toInt()
                val agentTurnSeconds =
                    prod
                        .getProperty(
                            "slack.app.agent.turns.shutdown-await-seconds",
                        ).toString()
                        .toInt()
                val relayAwaitSeconds =
                    (
                        ReflectionTestUtils.getField(
                            AsyncConfig().relayTaskExecutor(appConfig = AppConfig()),
                            "awaitTerminationMillis",
                        ) as Long
                    ) / 1_000L
                val recordSeconds = RECORD_SHUTDOWN_WAIT.seconds.toInt()
                val producerCloses = 3
                val margin = 10
                val lifecyclePhases = 2 * phaseSeconds + recordSeconds
                val executorWaits =
                    relayAwaitSeconds.toInt() + agentTurnSeconds + DEFAULT_EXECUTOR_SHUTDOWN_AWAIT_SECONDS
                val required =
                    preStopSeconds + lifecyclePhases + producerCloses * PRODUCER_CLOSE_TIMEOUT_SECONDS + executorWaits +
                        margin

                then("a dispatch running at shutdown may finish: the listener phase and the relay wait cover one") {
                    relayAwaitSeconds shouldBeGreaterThanOrEqual RELAY_RECORD_TIME_BOUND.seconds
                    RECORD_SHUTDOWN_WAIT shouldBeGreaterThanOrEqualTo RELAY_RECORD_TIME_BOUND
                }

                then("the grace period covers every wait in the serial shutdown plus a margin") {
                    graceSeconds shouldBeGreaterThanOrEqual required
                }
            }
        }

        given("the Recreate rollout and the deploy workflow that waits for it") {
            val deployment =
                ClassPathResource("k8s/deployment.yaml").inputStream.use { stream ->
                    Yaml().loadAll(stream).toList().single { it.at("kind") == "Deployment" }
                }
            val podSpec = deployment.at("spec").at("template").at("spec")

            @Suppress("UNCHECKED_CAST")
            val container = (podSpec.at("containers") as List<Any?>).single()
            val workflow = File("../.github/workflows/deploy_action.yaml").inputStream().use { Yaml().load<Any?>(it) }
            val rolloutSeconds =
                Duration
                    .parse(
                        "PT" +
                            workflow
                                .at("env")
                                .at("DEPLOYMENT_ROLLOUT_TIMEOUT")
                                .toString()
                                .uppercase(),
                    ).seconds
            val deployJobSeconds = (workflow.at("jobs").at("deploy").at("timeout-minutes") as Int) * 60L

            then("the rollout timeout covers the old Pods' grace, the startup probe and the first readiness check") {
                deployment.at("spec").at("strategy").at("type") shouldBe "Recreate"
                val startupSeconds =
                    (container.at("startupProbe").at("periodSeconds") as Int) *
                        (container.at("startupProbe").at("failureThreshold") as Int)
                val readinessSeconds = container.at("readinessProbe").at("periodSeconds") as Int
                rolloutSeconds shouldBeGreaterThanOrEqual
                    (podSpec.at("terminationGracePeriodSeconds") as Int + startupSeconds + readinessSeconds).toLong()
            }

            then("the deploy job outlasts a rollout, the two-minute health check and a rollback rollout") {
                deployJobSeconds shouldBeGreaterThanOrEqual 2 * rolloutSeconds + 120L
            }
        }

        given("the relay and agent-turn executors and the EntityManagerFactory in one context") {
            val executorsShutDownWhenEmfCloses = AtomicReference<List<Boolean>>()
            val executors = AtomicReference<List<ThreadPoolTaskExecutor>>()
            val context =
                AnnotationConfigApplicationContext().apply {
                    addBeanFactoryPostProcessor(LazyInitializationBeanFactoryPostProcessor())
                    registerBean(AppConfig::class.java, Supplier { AppConfig() })
                    registerBean(
                        "entityManagerFactory",
                        DisposableBean::class.java,
                        Supplier {
                            DisposableBean {
                                val shutDown = executors.get().map { it.threadPoolExecutor.isShutdown }
                                executorsShutDownWhenEmfCloses.set(shutDown)
                            }
                        },
                    )
                    register(AsyncConfig::class.java, AgentConfiguration::class.java)
                    refresh()
                }
            executors.set(
                listOf("relayTaskExecutor", "agentTurnExecutor").map {
                    context.getBean(it, ThreadPoolTaskExecutor::class.java)
                },
            )
            context.getBean("entityManagerFactory")

            `when`("the context closes") {
                context.close()

                then("both executors are shut down before the EntityManagerFactory closes") {
                    executorsShutDownWhenEmfCloses.get() shouldBe listOf(true, true)
                }
            }
        }

        given("the relay and the Kafka listener registry in one context") {
            val relay = createRelayService(outboxRepository = mockk(relaxed = true))
            val relayRunningWhenListenersStop = AtomicReference<Boolean>()
            val context =
                AnnotationConfigApplicationContext().apply {
                    registerBean("relay", Lifecycle::class.java, Supplier { relay })
                    registerBean(
                        KafkaListenerConfigUtils.KAFKA_LISTENER_ENDPOINT_REGISTRY_BEAN_NAME,
                        KafkaListenerEndpointRegistry::class.java,
                        Supplier {
                            object : KafkaListenerEndpointRegistry() {
                                override fun stop(callback: Runnable) {
                                    relayRunningWhenListenersStop.set(relay.isRunning)
                                    super.stop(callback)
                                }
                            }
                        },
                    )
                    refresh()
                }

            `when`("the context closes") {
                context.close()

                then("the CDC listeners stop first, so a record they claim is still dispatched, then the relay stops") {
                    relayRunningWhenListenersStop.get() shouldBe true
                    relay.isRunning shouldBe false
                }
            }
        }

        given("the agent-turn executor with every thread busy and a turn still queued when its shutdown wait ends") {
            val configuration =
                AgentConfiguration(
                    appConfig =
                        AppConfig(
                            agent =
                                AppConfig.Agent(turns = AppConfig.Agent.Turns(shutdownAwaitSeconds = 1L)),
                        ),
                )
            val executor = configuration.agentTurnExecutor().apply { initialize() }
            val intake = configuration.agentTurnIntake(agentTurnExecutor = executor).apply { start() }
            val release = CountDownLatch(1)
            val outcomes = CopyOnWriteArrayList<String>()
            repeat(times = executor.corePoolSize) {
                executor.execute(
                    AgentTurn(
                        start = {
                            release.await(10L, TimeUnit.SECONDS)
                            outcomes.add("running finished")
                        },
                        onDiscard = { outcomes.add("running discarded") },
                    ),
                )
            }
            executor.execute(
                AgentTurn(start = { outcomes.add("queued ran") }, onDiscard = { outcomes.add("queued discarded") }),
            )

            `when`("the intake stops and the executor is destroyed") {
                intake.stop()
                executor.destroy()
                release.countDown()
                executor.threadPoolExecutor.awaitTermination(5L, TimeUnit.SECONDS)

                then("the queued turn is discarded, so its notice goes out, and never starts; running turns finish") {
                    outcomes shouldContain "queued discarded"
                    outcomes shouldNotContain "queued ran"
                    outcomes shouldNotContain "running discarded"
                    outcomes.count { it == "running finished" } shouldBe executor.corePoolSize
                }
            }
        }

        given("the agent-turn executor with one turn running and one queued when the context begins to close") {
            val configuration = AgentConfiguration(appConfig = AppConfig())
            val executor = configuration.agentTurnExecutor().apply { initialize() }
            val intake = configuration.agentTurnIntake(agentTurnExecutor = executor).apply { start() }
            val release = CountDownLatch(1)
            val ran = CopyOnWriteArrayList<String>()
            repeat(times = executor.corePoolSize) {
                executor.execute {
                    release.await(5L, TimeUnit.SECONDS)
                    ran.add("running")
                }
            }
            executor.execute { ran.add("queued") }

            `when`("its intake stops") {
                intake.stop()
                val refused = runCatching { executor.execute { ran.add("after stop") } }.exceptionOrNull()
                release.countDown()
                executor.threadPoolExecutor.awaitTermination(5L, TimeUnit.SECONDS)

                then("a new turn is refused, so the mention gets the busy notice, and the queued turn still runs") {
                    refused.shouldBeInstanceOf<RejectedExecutionException>()
                    intake.isRunning shouldBe false
                    ran.count { it == "running" } shouldBe executor.corePoolSize
                    ran shouldContain "queued"
                    ran shouldNotContain "after stop"
                }
            }
        }
    })
