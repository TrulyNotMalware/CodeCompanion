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
                    google.syncBatchSize shouldBe 20
                    google.syncMaxAttempts shouldBe 8
                    google.syncStuckMinutes shouldBe 10L
                    google.syncTickBudgetSeconds shouldBe 30L
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
                                "slack.app.calendar.google.sync-batch-size" to "5",
                                "slack.app.calendar.google.sync-max-attempts" to "3",
                                "slack.app.calendar.google.sync-stuck-minutes" to "15",
                                "slack.app.calendar.google.sync-tick-budget-seconds" to "45",
                            ),
                    ).calendar.google

                then("they bind and the secrets are masked in toString") {
                    google.enabled shouldBe true
                    google.clientId shouldBe "cid.apps.googleusercontent.com"
                    google.clientSecret shouldBe "GOCSPX-secret"
                    google.redirectUri shouldBe "https://bot.example.com/oauth/google/callback"
                    google.stateTtlMinutes shouldBe 5L
                    google.requestTimeoutSeconds shouldBe 20L
                    google.syncBatchSize shouldBe 5
                    google.syncMaxAttempts shouldBe 3
                    google.syncStuckMinutes shouldBe 15L
                    google.syncTickBudgetSeconds shouldBe 45L
                    google.toString() shouldContain "syncBatchSize=5"
                    google.toString() shouldContain "syncTickBudgetSeconds=45"
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

            val positiveSyncKeys =
                listOf("sync-max-attempts", "sync-batch-size", "sync-stuck-minutes", "sync-tick-budget-seconds")
            positiveSyncKeys.forEach { key ->
                `when`("$key is zero") {
                    then("binding fails and names the property") {
                        val failure =
                            shouldThrow<BindException> {
                                bind(properties = mapOf("slack.app.calendar.google.$key" to "0"))
                            }
                        generateSequence<Throwable>(failure) { it.cause }.last().message shouldContain
                            "calendar.google.$key must be positive"
                    }
                }
            }

            `when`("request-timeout-seconds is so long that the default sync-stuck-minutes could reset a live row") {
                then("binding fails and names both keys") {
                    val failure =
                        shouldThrow<BindException> {
                            bind(properties = mapOf("slack.app.calendar.google.request-timeout-seconds" to "120"))
                        }
                    val message = checkNotNull(generateSequence<Throwable>(failure) { it.cause }.last().message)
                    message shouldContain "calendar.google.sync-stuck-minutes must exceed the worker's worst path"
                    message shouldContain "calendar.google.request-timeout-seconds"
                }
            }

            `when`("request-timeout-seconds is raised together with sync-stuck-minutes") {
                then("it binds once the stuck threshold covers the worst path again") {
                    val google =
                        bind(
                            properties =
                                mapOf(
                                    "slack.app.calendar.google.request-timeout-seconds" to "120",
                                    "slack.app.calendar.google.sync-stuck-minutes" to "18",
                                ),
                        ).calendar.google
                    google.requestTimeoutSeconds shouldBe 120L
                    google.syncStuckMinutes shouldBe 18L
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
