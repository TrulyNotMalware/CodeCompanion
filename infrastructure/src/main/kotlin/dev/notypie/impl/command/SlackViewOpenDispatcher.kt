package dev.notypie.impl.command

import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.domain.command.entity.event.OpenViewEvent
import org.springframework.context.event.EventListener

/**
 * Synchronous listener for [OpenViewEvent]. Unlike [SlackEventAsyncDispatcher], this class is
 * intentionally NOT annotated with `@Async` — `views.open` requires the originating
 * `trigger_id`, which expires 3 seconds after Slack issued it. Moving to a pool thread would
 * introduce enough latency (scheduler queueing, thread context switch) to race the expiry on
 * a loaded host.
 *
 * Delegates to [MessageDispatcher.dispatchImmediate], which calls `slack.methods().viewsOpen`
 * inline and publishes [dev.notypie.domain.command.entity.event.DeclineModalOpenFailedEvent]
 * on failure so the application layer can record the decline with a fallback reason.
 */
class SlackViewOpenDispatcher(
    private val messageDispatcher: MessageDispatcher,
) {
    @EventListener
    fun listenOpenViewEvent(event: OpenViewEvent) {
        messageDispatcher.dispatchImmediate(event = event.payload)
    }
}
