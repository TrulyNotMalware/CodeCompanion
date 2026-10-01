package dev.notypie.impl.command

import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.command.event.OpenViewEvent
import org.springframework.context.event.EventListener

// Not @Async: Slack's trigger_id for views.open expires 3s after issue; async latency could race that expiry.
class SlackViewOpenDispatcher(
    private val messageDispatcher: MessageDispatcher,
) {
    @EventListener
    fun listenOpenViewEvent(event: OpenViewEvent) {
        ViewOpenDeferral.runOrDefer { messageDispatcher.dispatchImmediate(event = event.payload) }
    }
}

object ViewOpenDeferral {
    private val pending = ThreadLocal<MutableList<() -> Unit>>()

    fun <T> afterBoundary(block: () -> T): T {
        if (pending.get() != null) return block()
        val opens = mutableListOf<() -> Unit>()
        pending.set(opens)
        val result =
            try {
                block()
            } finally {
                pending.remove()
            }
        opens.forEach { it() }
        return result
    }

    internal fun runOrDefer(open: () -> Unit) {
        val opens = pending.get()
        if (opens == null) open() else opens.add(open)
    }
}
