package dev.notypie.impl.command

import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import dev.notypie.domain.common.event.EventPublisher
import org.springframework.context.ApplicationEventPublisher
import org.springframework.kafka.core.KafkaTemplate
import java.util.Queue

class KafkaEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val applicationEventPublisher: ApplicationEventPublisher
): EventPublisher{
    override fun publishEvent(events: Queue<CommandEvent<EventPayload>>) {
        events.forEach { event ->
            when(event.isInternal) {
                true -> this.applicationEventPublisher.publishEvent(event)
                false -> this.kafkaTemplate.send(
                    event.destination, event.idempotencyKey.toString(),event.payload
                )
            }
        }
    }
}