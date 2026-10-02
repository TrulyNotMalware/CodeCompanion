package dev.notypie.impl.command

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.kafka.core.KafkaTemplate
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// Unchecked on purpose: Spring commits a transaction on a checked exception, so a failed publish must not be one.
class KafkaPublishException(
    event: CommandEvent<EventPayload>,
    cause: Throwable,
) : RuntimeException(
        "Kafka publish failed for destination=${event.destination} idempotencyKey=${event.idempotencyKey}",
        cause,
    )

class KafkaEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val sendTimeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS,
) : EventPublisher {
    private val log = KotlinLogging.logger {}

    companion object {
        const val DEFAULT_SEND_TIMEOUT_MILLIS: Long = 5_000L
    }

    override fun publishEvent(events: EventQueue<CommandEvent<EventPayload>>) =
        events.forEach { event ->
            when (event.isInternal) {
                true -> applicationEventPublisher.publishEvent(event)
                false -> sendToKafka(event = event)
            }
        }

    private fun sendToKafka(event: CommandEvent<EventPayload>) {
        val future =
            kafkaTemplate.send(
                event.destination,
                event.idempotencyKey.toString(),
                event.payload,
            )

        try {
            future.get(sendTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            log.error(e) {
                "Kafka send timed out after ${sendTimeoutMillis}ms for destination=${event.destination} " +
                    "idempotencyKey=${event.idempotencyKey}"
            }
            throw KafkaPublishException(event = event, cause = e)
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            log.error(cause) {
                "Kafka send failed for destination=${event.destination} idempotencyKey=${event.idempotencyKey}"
            }
            throw cause as? RuntimeException ?: KafkaPublishException(event = event, cause = cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.error(e) {
                "Kafka send interrupted for destination=${event.destination} idempotencyKey=${event.idempotencyKey}"
            }
            throw KafkaPublishException(event = event, cause = e)
        }
    }
}
