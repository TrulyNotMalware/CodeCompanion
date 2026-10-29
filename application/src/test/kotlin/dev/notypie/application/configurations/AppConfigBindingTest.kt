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

        given("outbox.retention properties") {
            fun rootCauseOf(properties: Map<String, String>): String {
                val failure = shouldThrow<BindException> { bind(properties = properties) }
                return checkNotNull(generateSequence<Throwable>(failure) { it.cause }.last().message)
            }

            `when`("none are set") {
                val retention = bind(properties = mapOf("slack.app.api.token" to "xoxb")).outbox.retention

                then("delivered rows are kept 14 days, deleted 1000 at a time") {
                    retention.days shouldBe 14L
                    retention.batchSize shouldBe 1_000
                }
            }

            `when`("both keys are set at their accepted bounds") {
                val lowest =
                    bind(
                        properties =
                            mapOf(
                                "slack.app.outbox.retention.days" to "1",
                                "slack.app.outbox.retention.batch-size" to "1",
                            ),
                    ).outbox.retention
                val highest =
                    bind(
                        properties =
                            mapOf(
                                "slack.app.outbox.retention.days" to "3650",
                                "slack.app.outbox.retention.batch-size" to "10000",
                            ),
                    ).outbox.retention

                then("they bind") {
                    lowest.days shouldBe 1L
                    lowest.batchSize shouldBe 1
                    highest.days shouldBe 3_650L
                    highest.batchSize shouldBe 10_000
                }
            }

            listOf(
                Triple("days", "0", "outbox.retention.days must be between 1 and 3650"),
                Triple("days", "-1", "outbox.retention.days must be between 1 and 3650"),
                Triple("days", "3651", "outbox.retention.days must be between 1 and 3650"),
                Triple("batch-size", "0", "outbox.retention.batch-size must be between 1 and 10000"),
                Triple("batch-size", "-5", "outbox.retention.batch-size must be between 1 and 10000"),
                Triple("batch-size", "10001", "outbox.retention.batch-size must be between 1 and 10000"),
            ).forEach { (key, value, expected) ->
                `when`("$key is $value") {
                    then("binding fails and names the property and its range") {
                        val properties = mapOf("slack.app.outbox.retention.$key" to value)
                        rootCauseOf(properties = properties) shouldContain expected
                    }
                }
            }
        }

        given("meeting.reminder.offsets-minutes") {
            `when`("every offset is positive") {
                val reminder =
                    bind(properties = mapOf("slack.app.meeting.reminder.offsets-minutes" to "30,10,1")).meeting.reminder

                then("they bind in order") {
                    reminder.offsetsMinutes shouldBe listOf(30, 10, 1)
                }
            }

            listOf("0", "15,0", "-5").forEach { value ->
                `when`("the offsets are $value") {
                    then("binding fails and names the property") {
                        val failure =
                            shouldThrow<BindException> {
                                bind(properties = mapOf("slack.app.meeting.reminder.offsets-minutes" to value))
                            }
                        val rootCause = checkNotNull(generateSequence<Throwable>(failure) { it.cause }.last().message)
                        rootCause shouldContain "meeting.reminder.offsets-minutes must be positive"
                    }
                }
            }
        }

        given("agent.retention properties") {
            fun rootCauseOf(properties: Map<String, String>): String {
                val failure = shouldThrow<BindException> { bind(properties = properties) }
                return checkNotNull(generateSequence<Throwable>(failure) { it.cause }.last().message)
            }

            `when`("none are set") {
                val retention = bind(properties = mapOf("slack.app.api.token" to "xoxb")).agent.retention

                then("ask turns are kept 14 days and the history 180 days, deleted 1000 rows at a time") {
                    retention.askTurnDays shouldBe 14L
                    retention.historyDays shouldBe 180L
                    retention.batchSize shouldBe 1_000
                }
            }

            `when`("every key is set at its lowest accepted value") {
                val retention =
                    bind(
                        properties =
                            mapOf(
                                "slack.app.agent.retention.ask-turn-days" to "2",
                                "slack.app.agent.retention.history-days" to "91",
                                "slack.app.agent.retention.batch-size" to "1",
                            ),
                    ).agent.retention

                then("they bind") {
                    retention.askTurnDays shouldBe 2L
                    retention.historyDays shouldBe 91L
                    retention.batchSize shouldBe 1
                }
            }

            `when`("every key is set at its highest accepted value") {
                val retention =
                    bind(
                        properties =
                            mapOf(
                                "slack.app.agent.retention.ask-turn-days" to "3650",
                                "slack.app.agent.retention.history-days" to "3650",
                                "slack.app.agent.retention.batch-size" to "10000",
                            ),
                    ).agent.retention

                then("they bind") {
                    retention.askTurnDays shouldBe 3_650L
                    retention.historyDays shouldBe 3_650L
                    retention.batchSize shouldBe 10_000
                }
            }

            listOf(
                Triple("history-days", "90", "agent.retention.history-days must be between 91"),
                Triple("history-days", "3651", "agent.retention.history-days must be between 91"),
                Triple("ask-turn-days", "0", "agent.retention.ask-turn-days must be between 1 and 3650"),
                Triple("ask-turn-days", "3651", "agent.retention.ask-turn-days must be between 1 and 3650"),
                Triple("batch-size", "0", "agent.retention.batch-size must be between 1 and 10000"),
                Triple("batch-size", "10001", "agent.retention.batch-size must be between 1 and 10000"),
            ).forEach { (key, value, expected) ->
                `when`("$key is $value") {
                    then("binding fails and names the property and its range") {
                        val properties = mapOf("slack.app.agent.retention.$key" to value)
                        rootCauseOf(properties = properties) shouldContain expected
                    }
                }
            }

            `when`("ask-turn-days does not outlast the outbox's give-up window") {
                then("one day against the default 24 hours fails and names both keys") {
                    val message = rootCauseOf(properties = mapOf("slack.app.agent.retention.ask-turn-days" to "1"))
                    message shouldContain "agent.retention.ask-turn-days must exceed"
                    message shouldContain "outbox.polling.give-up-after-hours"
                }

                then("a give-up window raised to the default 14 days fails") {
                    val properties = mapOf("slack.app.outbox.polling.give-up-after-hours" to "336")
                    rootCauseOf(properties = properties) shouldContain "agent.retention.ask-turn-days must exceed"
                }

                then("a give-up window too large for a Duration fails with the same message, not an overflow") {
                    val properties = mapOf("slack.app.outbox.polling.give-up-after-hours" to Long.MAX_VALUE.toString())
                    rootCauseOf(properties = properties) shouldContain "agent.retention.ask-turn-days must exceed"
                }
            }

            `when`("one day of ask-turn retention meets a give-up window lowered below it") {
                then("it binds") {
                    val appConfig =
                        bind(
                            properties =
                                mapOf(
                                    "slack.app.agent.retention.ask-turn-days" to "1",
                                    "slack.app.outbox.polling.give-up-after-hours" to "23",
                                ),
                        )
                    appConfig.agent.retention.askTurnDays shouldBe 1L
                    appConfig.outbox.polling.giveUpAfterHours shouldBe 23L
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
