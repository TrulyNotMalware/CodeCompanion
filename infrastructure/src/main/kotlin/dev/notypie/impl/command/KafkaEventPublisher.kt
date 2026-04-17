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

/**
 * Publishes CommandEvents. Internal events flow through the Spring application bus;
 * external events are written to Kafka.
 *
 * Kafka sends are awaited with a bounded timeout so that broker-side failures surface as
 * exceptions to the caller (CommandExecutor) and can be handled transactionally.
 */
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
            throw e
        } catch (e: ExecutionException) {
            log.error(e.cause ?: e) {
                "Kafka send failed for destination=${event.destination} idempotencyKey=${event.idempotencyKey}"
            }
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.error(e) {
                "Kafka send interrupted for destination=${event.destination} idempotencyKey=${event.idempotencyKey}"
            }
            throw e
        }
    }
}
