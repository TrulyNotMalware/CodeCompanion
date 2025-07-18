package dev.notypie.impl.command

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import dev.notypie.domain.common.event.EventPublisher
import org.springframework.context.ApplicationEventPublisher

class AppEventPublisher(
    private val applicationEventPublisher: ApplicationEventPublisher
): EventPublisher {

    override fun publishEvent(events: EventQueue<CommandEvent<EventPayload>>){
        events.forEach { applicationEventPublisher.publishEvent(it) }
    }

}