package dev.notypie.application.configurations

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.kafka.clients.producer.ProducerConfig
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import java.time.Duration

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

                then("the JSON producer and the dead-letter bytes producer close within the budgeted timeout") {
                    contextRunner.run { context ->
                        val factory = context.getBean(ProducerFactory::class.java)
                        factory.shouldBeInstanceOf<DefaultKafkaProducerFactory<*, *>>().physicalCloseTimeout shouldBe
                            Duration.ofSeconds(PRODUCER_CLOSE_TIMEOUT_SECONDS.toLong())
                        deadLetterBytesProducerFactory(
                            jsonTemplate = context.getBean(KafkaTemplate::class.java) as KafkaTemplate<String, Any>,
                        ).physicalCloseTimeout shouldBe Duration.ofSeconds(PRODUCER_CLOSE_TIMEOUT_SECONDS.toLong())
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

        given("the CDC consumer without the Kafka event publisher") {
            val cdcOnly =
                ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration::class.java))
                    .withUserConfiguration(
                        KafkaObservationConvention::class.java,
                        KafkaConsumerConfiguration::class.java,
                    ).withBean(SimpleMeterRegistry::class.java)
                    .withPropertyValues("spring.kafka.bootstrap-servers=127.0.0.1:1")

            `when`("Boot's producer factory backs the dead-letter template") {
                then("it closes within the budgeted timeout too") {
                    cdcOnly.run { context ->
                        context
                            .getBean(ProducerFactory::class.java)
                            .shouldBeInstanceOf<DefaultKafkaProducerFactory<*, *>>()
                            .physicalCloseTimeout shouldBe Duration.ofSeconds(PRODUCER_CLOSE_TIMEOUT_SECONDS.toLong())
                    }
                }
            }
        }
    })
