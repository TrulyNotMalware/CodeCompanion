package dev.notypie.impl.command

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import org.springframework.context.ApplicationEventPublisher
import org.springframework.kafka.core.KafkaTemplate

class KafkaEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val applicationEventPublisher: ApplicationEventPublisher,
) : EventPublisher {
    override fun publishEvent(events: EventQueue<CommandEvent<EventPayload>>) {
        events.forEach { event ->
            when (event.isInternal) {
                true -> applicationEventPublisher.publishEvent(event)
                false ->
                    kafkaTemplate.send(
                        event.destination,
                        event.idempotencyKey.toString(),
                        event.payload,
                    )
            }
        }
    }
}
