package dev.notypie.domain.command

import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload

interface EventQueue<T : CommandEvent<EventPayload>> : Iterable<T> {
    fun offer(event: T)

    fun poll(): T?

    fun peek(): T?

    fun isEmpty(): Boolean

    fun snapshot(): List<T>

    fun containsExternalEvent(): Boolean

    val size: Int
}

class DefaultEventQueue<T : CommandEvent<EventPayload>>(
    private val eventQueue: ArrayDeque<T> = ArrayDeque(),
) : EventQueue<T> {
    // Cache
    private var externalEventCount: Int = 0

    override fun iterator(): Iterator<T> = eventQueue.iterator()

    override fun offer(event: T) {
        eventQueue.addLast(event)
        if (!event.isInternal) externalEventCount++
    }

    override fun poll(): T? {
        val polled = eventQueue.removeFirstOrNull()
        if (polled != null && !polled.isInternal) externalEventCount--
        return polled
    }

    override fun containsExternalEvent(): Boolean = externalEventCount > 0

    override fun peek(): T? = eventQueue.firstOrNull()

    override fun isEmpty(): Boolean = eventQueue.isEmpty()

    override fun snapshot(): List<T> = eventQueue.toList()

    override val size: Int
        get() = eventQueue.size
}
