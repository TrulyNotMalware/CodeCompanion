package dev.notypie.domain.common.event

import dev.notypie.domain.command.EventQueue

interface EventPublisher {
    fun publishEvent(events: EventQueue<CommandEvent<EventPayload>>)
}