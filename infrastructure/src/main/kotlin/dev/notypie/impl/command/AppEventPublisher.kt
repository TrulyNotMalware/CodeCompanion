package dev.notypie.impl.command

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import org.springframework.context.ApplicationEventPublisher

class AppEventPublisher(
    private val applicationEventPublisher: ApplicationEventPublisher,
) : EventPublisher {
    override fun publishEvent(events: EventQueue<CommandEvent<EventPayload>>) =
        events.forEach { applicationEventPublisher.publishEvent(it) }
}
