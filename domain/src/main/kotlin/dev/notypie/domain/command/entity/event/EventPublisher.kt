package dev.notypie.domain.command.entity.event

import dev.notypie.domain.command.DefaultEventQueue
import dev.notypie.domain.command.EventQueue

interface EventPublisher {
    fun publishEvent(events: EventQueue<CommandEvent<EventPayload>>)
}

fun EventPublisher.publishOne(event: CommandEvent<EventPayload>) {
    val queue = DefaultEventQueue<CommandEvent<EventPayload>>()
    queue.offer(event = event)
    publishEvent(events = queue)
}
