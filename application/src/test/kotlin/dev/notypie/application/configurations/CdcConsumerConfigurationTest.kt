package dev.notypie.application.configurations

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer

class CdcConsumerConfigurationTest :
    BehaviorSpec({
        val cdcProperties =
            arrayOf(
                "slack.app.mode.outbox-reading-strategy=CDC",
                "slack.app.mode.cdc.topic=cdc.code_companion.outbox_message",
                "spring.kafka.bootstrap-servers=127.0.0.1:1",
                "spring.kafka.admin.auto-create=false",
            )

        given("the CDC consumer mode") {
            `when`("Boot's Kafka auto-configuration provides the KafkaTemplate") {
                val contextRunner =
                    ApplicationContextRunner()
                        .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration::class.java))
                        .withUserConfiguration(CdcConsumerConfiguration::class.java, AppConfigBinding::class.java)
                        .withBean(SimpleMeterRegistry::class.java)
                        .withPropertyValues(*cdcProperties)

                then("the dead-letter recovery publishes through that template") {
                    contextRunner.run { context ->
                        context.startupFailure shouldBe null
                        context
                            .getBean(CdcDeadLetterRecovery::class.java)
                            .recoverer.deadLetter
                            .shouldBeInstanceOf<CountingRecordRecoverer>()
                            .delegate
                            .shouldBeInstanceOf<DeadLetterPublishingRecoverer>()
                    }
                }
            }

            `when`("no KafkaTemplate bean exists") {
                val contextRunner =
                    ApplicationContextRunner()
                        .withUserConfiguration(CdcConsumerConfiguration::class.java, AppConfigBinding::class.java)
                        .withBean(KafkaProperties::class.java)
                        .withBean(SimpleMeterRegistry::class.java)
                        .withPropertyValues(*cdcProperties)

                then("startup fails instead of running a consumer that can only drop poison records") {
                    contextRunner.run { context ->
                        context.startupFailure.shouldBeInstanceOf<Throwable>().stackTraceToString() shouldContain
                            "KafkaTemplate"
                    }
                }
            }
        }
    })
