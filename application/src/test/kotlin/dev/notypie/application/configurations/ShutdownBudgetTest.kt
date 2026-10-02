package dev.notypie.application.configurations

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.yaml.snakeyaml.Yaml
import java.time.Duration

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
                val required =
                    preStopSeconds + 2 * phaseSeconds + RELAY_SHUTDOWN_AWAIT_SECONDS + agentTurnSeconds +
                        DEFAULT_EXECUTOR_SHUTDOWN_AWAIT_SECONDS

                then("the grace period covers preStop, both lifecycle phases and every executor wait") {
                    graceSeconds shouldBeGreaterThanOrEqual required
                }
            }
        }
    })
