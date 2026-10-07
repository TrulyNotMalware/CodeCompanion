package dev.notypie.application.configurations

import dev.notypie.repository.cve.schema.CveSourceType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
    })
