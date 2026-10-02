package dev.notypie.application.configurations

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.PropertySource
import org.springframework.core.io.ClassPathResource

class ProfileYamlTest :
    BehaviorSpec({
        fun profile(name: String): PropertySource<*> =
            YamlPropertySourceLoader().load(name, ClassPathResource("application-$name.yaml")).single()

        given("the slack-live profile, whose port is tunnelled to the internet") {
            val yaml = profile(name = "slack-live")

            `when`("its actuator exposure and datasource password are read") {
                val exposure = yaml.getProperty("management.endpoints.web.exposure.include").toString()
                val passwordHasNoDefault =
                    yaml.getProperty("spring.datasource.password").toString() == "\${DATABASE_USER_PWD}"

                then("only health is exposed") {
                    exposure shouldBe "health"
                }

                then("the password comes from the environment with no committed default") {
                    passwordHasNoDefault shouldBe true
                }
            }
        }

        given("the prod and dev profiles") {
            `when`("their actuator exposure is read") {
                val exposures =
                    listOf("prod", "dev").associateWith {
                        profile(name = it).getProperty("management.endpoints.web.exposure.include").toString()
                    }

                then("prod caches the aggregate health response, whose outbox indicator queries the database") {
                    profile(
                        name = "prod",
                    ).getProperty("management.endpoint.health.cache.time-to-live").toString() shouldBe
                        "10s"
                }

                then("Prometheus can scrape them") {
                    exposures shouldBe
                        mapOf("prod" to "health,info,metrics,prometheus", "dev" to "health,info,metrics,prometheus")
                }
            }
        }
    })
