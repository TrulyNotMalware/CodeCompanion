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
        messageDispatcher.dispatchImmediate(event = event.payload)
    }
}
