package dev.notypie.impl.command

import dev.notypie.domain.command.DefaultEventQueue
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

class KafkaEventPublisherFailureTest :
    BehaviorSpec({
        fun publisherSending(result: CompletableFuture<SendResult<String, Any>>): KafkaEventPublisher {
            val template = mockk<KafkaTemplate<String, Any>>()
            every { template.send(any<String>(), any<String>(), any()) } returns result
            return KafkaEventPublisher(
                kafkaTemplate = template,
                applicationEventPublisher = mockk(relaxed = true),
                sendTimeoutMillis = 50L,
            )
        }

        fun externalEvents() =
            DefaultEventQueue<CommandEvent<EventPayload>>().apply {
                offer(
                    event =
                        TestKafkaCommandEvent(
                            destination = "external-topic",
                            isInternal = false,
                            payload = TestKafkaPayload(value = "x"),
                        ),
                )
            }

        given("an external event whose Kafka send never completes") {
            `when`("it is published") {
                val failure =
                    runCatching {
                        publisherSending(
                            result = CompletableFuture(),
                        ).publishEvent(events = externalEvents())
                    }

                then("the timeout surfaces as an unchecked exception, so @Transactional callers roll back") {
                    failure
                        .exceptionOrNull()
                        .shouldBeInstanceOf<KafkaPublishException>()
                        .cause
                        .shouldBeInstanceOf<TimeoutException>()
                }
            }
        }

        given("an external event whose send fails with a checked cause") {
            `when`("it is published") {
                val failure =
                    runCatching {
                        publisherSending(result = CompletableFuture.failedFuture(IOException("broker gone")))
                            .publishEvent(events = externalEvents())
                    }

                then("the checked cause is wrapped in the unchecked publish exception") {
                    failure
                        .exceptionOrNull()
                        .shouldBeInstanceOf<KafkaPublishException>()
                        .cause
                        .shouldBeInstanceOf<IOException>()
                }
            }
        }

        given("an external event whose send fails with an unchecked cause") {
            `when`("it is published") {
                val failure =
                    runCatching {
                        publisherSending(result = CompletableFuture.failedFuture(KafkaException("not authorized")))
                            .publishEvent(events = externalEvents())
                    }

                then("the unchecked cause is rethrown as it is") {
                    failure.exceptionOrNull().shouldBeInstanceOf<KafkaException>().message shouldBe "not authorized"
                }
            }
        }

        given("an external event published on an interrupted thread") {
            `when`("it is published") {
                Thread.currentThread().interrupt()
                val failure =
                    runCatching {
                        publisherSending(
                            result = CompletableFuture(),
                        ).publishEvent(events = externalEvents())
                    }
                val keptInterrupt = Thread.interrupted()

                then("the interrupt is kept and surfaces unchecked") {
                    failure
                        .exceptionOrNull()
                        .shouldBeInstanceOf<KafkaPublishException>()
                        .cause
                        .shouldBeInstanceOf<InterruptedException>()
                    keptInterrupt shouldBe true
                }
            }
        }
    })
