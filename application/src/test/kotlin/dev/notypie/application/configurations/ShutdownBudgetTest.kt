package dev.notypie.application.configurations

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.io.ClassPathResource
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.yaml.snakeyaml.Yaml
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

class ShutdownBudgetTest :
    BehaviorSpec({
        @Suppress("UNCHECKED_CAST")
        fun Any?.at(key: String): Any? = (this as Map<String, Any?>)[key]

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
                // Each waits up to the phase timeout, one after another: the scheduler (a running job), the Kafka
                // listener containers (the record in hand) and the web server drain.
                val lifecyclePhases = 3

                val producerCloses = 3
                val required =
                    preStopSeconds + lifecyclePhases * phaseSeconds + RELAY_SHUTDOWN_AWAIT_SECONDS + agentTurnSeconds +
                        DEFAULT_EXECUTOR_SHUTDOWN_AWAIT_SECONDS + producerCloses * PRODUCER_CLOSE_TIMEOUT_SECONDS

                then(
                    "the grace period covers preStop, the three lifecycle phases, every executor wait and every producer close",
                ) {
                    graceSeconds shouldBeGreaterThanOrEqual required
                }
            }
        }

        given("the relay executor and the EntityManagerFactory in one context") {
            val executorShutDownWhenEmfCloses = AtomicReference<Boolean>()
            val relayExecutor = AtomicReference<ThreadPoolTaskExecutor>()
            val context =
                AnnotationConfigApplicationContext().apply {
                    registerBean(AppConfig::class.java, Supplier { AppConfig() })
                    registerBean(
                        "entityManagerFactory",
                        DisposableBean::class.java,
                        Supplier {
                            DisposableBean {
                                executorShutDownWhenEmfCloses.set(relayExecutor.get().threadPoolExecutor.isShutdown)
                            }
                        },
                        { definition -> definition.isLazyInit = true },
                    )
                    register(AsyncConfig::class.java)
                    refresh()
                }
            relayExecutor.set(context.getBean("relayTaskExecutor", ThreadPoolTaskExecutor::class.java))
            context.getBean("entityManagerFactory")

            `when`("the context closes") {
                context.close()

                then("the relay executor is shut down before the EntityManagerFactory closes") {
                    executorShutDownWhenEmfCloses.get() shouldBe true
                }
            }
        }
    })
