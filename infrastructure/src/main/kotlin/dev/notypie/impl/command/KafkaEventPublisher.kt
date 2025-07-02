package dev.notypie.impl.command

import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import dev.notypie.domain.common.event.EventPublisher
import org.apache.kafka.clients.producer.KafkaProducer
import org.springframework.context.ApplicationEventPublisher
import java.util.Queue

class KafkaEventPublisher(
    private val kafkaProducer: KafkaProducer<String, Any>,
    private val applicationEventPublisher: ApplicationEventPublisher
): EventPublisher{
    override fun publishEvent(events: Queue<CommandEvent<EventPayload>>) {

    }

}