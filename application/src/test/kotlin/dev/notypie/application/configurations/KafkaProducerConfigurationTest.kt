package dev.notypie.application.configurations

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.kafka.clients.producer.ProducerConfig
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

class KafkaProducerConfigurationTest :
    BehaviorSpec({
        val contextRunner =
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration::class.java))
                .withUserConfiguration(KafkaEventPublisherConfiguration::class.java)
                .withBean(SimpleMeterRegistry::class.java)
                .withPropertyValues(
                    "slack.app.mode.event-publisher=KAFKA",
                    "spring.kafka.bootstrap-servers=127.0.0.1:1",
                    "spring.kafka.producer.acks=all",
                    "spring.kafka.properties.client.rack=test-rack",
                )

        given("the Kafka event publisher mode") {
            `when`("the producer beans are created") {
                then("the KafkaTemplate uses the ProducerFactory bean, not a second instance") {
                    contextRunner.run { context ->
                        context.getBean(KafkaTemplate::class.java).producerFactory shouldBeSameInstanceAs
                            context.getBean(ProducerFactory::class.java)
                    }
                }

                then("spring.kafka producer and shared properties reach the producer") {
                    contextRunner.run { context ->
                        val configuration = context.getBean(ProducerFactory::class.java).configurationProperties
                        configuration[ProducerConfig.ACKS_CONFIG] shouldBe "all"
                        configuration["client.rack"] shouldBe "test-rack"
                    }
                }
            }
        }
    })
