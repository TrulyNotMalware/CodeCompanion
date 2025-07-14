package dev.notypie.domain.command

import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload

interface EventQueue<T: CommandEvent<EventPayload>> : Iterable<T> {
    fun offer(event: T)
    fun poll(): T?
    fun peek(): T?
    fun isEmpty(): Boolean

    fun snapshot(): List<T>
    fun containsExternalEvent(): Boolean
    val size: Int
}


class DefaultEventQueue<T: CommandEvent<EventPayload>>(
    private val eventQueue: ArrayDeque<T> = ArrayDeque(),
): EventQueue<T> {
    //Cache
    private var hasExternalEvent: Boolean = false

    override fun iterator(): Iterator<T> = this.eventQueue.iterator()

    override fun offer(event: T){
        this.eventQueue.addLast(element = event)
        if( !event.isInternal ) hasExternalEvent = true
    }

    override fun poll(): T?{
        val polled = this.eventQueue.removeFirstOrNull()
        if( polled != null && !polled.isInternal )
            hasExternalEvent = eventQueue.any { !it.isInternal }
        return polled
    }

    override fun peek(): T? = this.eventQueue.firstOrNull()

    override fun isEmpty(): Boolean = this.eventQueue.isEmpty()

    override fun containsExternalEvent(): Boolean = this.hasExternalEvent

    override fun snapshot(): List<T> = this.eventQueue.toList()

    override val size: Int
        get() = this.eventQueue.size

}