package dev.notypie.application.configurations

import dev.notypie.repository.cve.schema.CveSourceType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class AppConfigBindingTest :
    BehaviorSpec({
        fun bind(properties: Map<String, String>): AppConfig =
            Binder(MapConfigurationPropertySource(properties))
                .bind(APP_CONFIG_PROPERTIES_PREFIX, AppConfig::class.java)
                .get()

        given("a CVE topic entry") {
            `when`("it has no source-type") {
                val failure =
                    shouldThrow<BindException> {
                        bind(properties = mapOf("slack.app.cve.topics[0].key" to "cve-java"))
                    }

                then("binding fails and the failure names the entry and the missing property") {
                    failure.message shouldContain "slack.app.cve.topics[0]"
                    generateSequence<Throwable>(failure) { it.cause }.last().message shouldContain "sourceType"
                }
            }

            `when`("it is complete") {
                val appConfig =
                    bind(
                        properties =
                            mapOf(
                                "slack.app.cve.topics[0].key" to "cve-java",
                                "slack.app.cve.topics[0].source-type" to "NVD_CVE",
                            ),
                    )

                then("it binds with the given source type") {
                    val topic = appConfig.cve.topics.single()
                    topic.key shouldBe "cve-java"
                    topic.sourceType shouldBe CveSourceType.NVD_CVE
                }
            }
        }

        given("calendar.google properties") {
            `when`("none are set") {
                val google = bind(properties = mapOf("slack.app.api.token" to "xoxb")).calendar.google

                then("the integration is off with empty credentials and a 10 minute state TTL") {
                    google.enabled shouldBe false
                    google.clientId shouldBe ""
                    google.clientSecret shouldBe ""
                    google.redirectUri shouldBe ""
                    google.tokenEncryptionKey shouldBe ""
                    google.stateTtlMinutes shouldBe 10L
                    google.requestTimeoutSeconds shouldBe 10L
                }
            }

            `when`("every key is set") {
                val google =
                    bind(
                        properties =
                            mapOf(
                                "slack.app.calendar.google.enabled" to "true",
                                "slack.app.calendar.google.client-id" to "cid.apps.googleusercontent.com",
                                "slack.app.calendar.google.client-secret" to "GOCSPX-secret",
                                "slack.app.calendar.google.redirect-uri" to
                                    "https://bot.example.com/oauth/google/callback",
                                "slack.app.calendar.google.token-encryption-key" to
                                    "a2V5a2V5a2V5a2V5a2V5a2V5a2V5a2V5a2V5a2V5a2V5",
                                "slack.app.calendar.google.state-ttl-minutes" to "5",
                                "slack.app.calendar.google.request-timeout-seconds" to "20",
                            ),
                    ).calendar.google

                then("they bind and the secrets are masked in toString") {
                    google.enabled shouldBe true
                    google.clientId shouldBe "cid.apps.googleusercontent.com"
                    google.clientSecret shouldBe "GOCSPX-secret"
                    google.redirectUri shouldBe "https://bot.example.com/oauth/google/callback"
                    google.stateTtlMinutes shouldBe 5L
                    google.requestTimeoutSeconds shouldBe 20L
                    google.toString() shouldNotContain "GOCSPX-secret"
                    google.toString() shouldNotContain "a2V5a2V5"
                    google.toString() shouldContain "cid.apps.googleusercontent.com"
                }
            }

            `when`("state-ttl-minutes is zero") {
                then("binding fails") {
                    shouldThrow<BindException> {
                        bind(properties = mapOf("slack.app.calendar.google.state-ttl-minutes" to "0"))
                    }
                }
            }
        }

        given("unresolved secret placeholders") {
            `when`("the calendar client secret is still a \${...} placeholder") {
                val appConfig =
                    bind(
                        properties =
                            mapOf(
                                "slack.app.api.token" to "xoxb",
                                "slack.app.calendar.google.client-secret" to "\${GOOGLE_OAUTH_CLIENT_SECRET}",
                            ),
                    )

                then("the secrets check names that key") {
                    shouldThrow<IllegalStateException> { appConfig.requireUsableSecrets() }.message shouldContain
                        "slack.app.calendar.google.client-secret"
                }
            }
        }
    })
