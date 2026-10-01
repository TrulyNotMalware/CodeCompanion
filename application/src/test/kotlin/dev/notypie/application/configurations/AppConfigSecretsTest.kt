package dev.notypie.application.configurations

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class AppConfigSecretsTest :
    BehaviorSpec({
        given("a bound AppConfig") {
            `when`("the Slack token kept its placeholder because SLACK_API_TOKEN is missing") {
                val appConfig = AppConfig(api = AppConfig.Api(token = "\${SLACK_API_TOKEN}"))

                then("startup fails and names the key, not a value") {
                    val failure = shouldThrow<IllegalStateException> { appConfig.requireUsableSecrets() }
                    failure.message shouldContain "slack.app.api.token"
                }
            }

            `when`("the sidecar bearer secret kept its placeholder") {
                val appConfig =
                    AppConfig(
                        api = AppConfig.Api(token = "xoxb-real"),
                        agent =
                            AppConfig.Agent(
                                sidecar = AppConfig.Agent.Sidecar(bearerSecret = "\${SIDECAR_BEARER_SECRET}"),
                            ),
                    )

                then("startup fails and does not print the token") {
                    val failure = shouldThrow<IllegalStateException> { appConfig.requireUsableSecrets() }
                    failure.message shouldContain "slack.app.agent.sidecar.bearer-secret"
                    failure.message shouldNotContain "xoxb-real"
                }
            }

            `when`("the Slack token is blank") {
                val appConfig = AppConfig(api = AppConfig.Api(token = ""))

                then("startup fails") {
                    shouldThrow<IllegalStateException> { appConfig.requireUsableSecrets() }
                }
            }

            `when`("the token is set and optional secrets are blank") {
                val appConfig = AppConfig(api = AppConfig.Api(token = "xoxb-real"))

                then("startup proceeds") {
                    shouldNotThrowAny { appConfig.requireUsableSecrets() }
                }
            }
        }
    })
