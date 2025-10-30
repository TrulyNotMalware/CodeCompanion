package dev.notypie.domain.command.entity.event

import dev.notypie.domain.command.EventQueue

interface EventPublisher {
    fun publishEvent(events: EventQueue<CommandEvent<EventPayload>>)
}
