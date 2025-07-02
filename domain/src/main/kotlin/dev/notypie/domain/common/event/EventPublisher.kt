package dev.notypie.domain.common.event

import java.util.Queue

interface EventPublisher {
    fun publishEvent(events: Queue<CommandEvent<EventPayload>>)
}