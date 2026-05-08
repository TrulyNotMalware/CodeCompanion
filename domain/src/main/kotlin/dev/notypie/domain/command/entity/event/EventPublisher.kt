package dev.notypie.domain.command.entity.event

import dev.notypie.domain.command.DefaultEventQueue
import dev.notypie.domain.command.EventQueue

interface EventPublisher {
    fun publishEvent(events: EventQueue<CommandEvent<EventPayload>>)
}

/**
 * Publishes a single domain event by wrapping it in a one-shot [DefaultEventQueue].
 * Centralizes the cast that callers had to repeat at every fire-and-forget publish site
 * (the publisher's queue type is invariant on `CommandEvent<EventPayload>`, but most
 * call sites construct a more-specific subtype like `SendSlackMessageEvent`).
 */
fun EventPublisher.publishOne(event: CommandEvent<out EventPayload>) {
    val queue = DefaultEventQueue<CommandEvent<EventPayload>>()
    @Suppress("UNCHECKED_CAST")
    queue.offer(event = event as CommandEvent<EventPayload>)
    publishEvent(events = queue)
}
