package dev.notypie.domain.command.entity.event

import dev.notypie.domain.command.DefaultEventQueue
import dev.notypie.domain.command.EventQueue

interface EventPublisher {
    fun publishEvent(events: EventQueue<CommandEvent<EventPayload>>)
}

/**
 * Publishes a single domain event by wrapping it in a one-shot [DefaultEventQueue].
 * Centralizes the boilerplate that callers had to repeat at every fire-and-forget publish site.
 * `CommandEvent` is covariant (`out T`), so a more-specific subtype like `GetMeetingListEvent`
 * is already accepted here without any cast.
 */
fun EventPublisher.publishOne(event: CommandEvent<EventPayload>) {
    val queue = DefaultEventQueue<CommandEvent<EventPayload>>()
    queue.offer(event = event)
    publishEvent(events = queue)
}
