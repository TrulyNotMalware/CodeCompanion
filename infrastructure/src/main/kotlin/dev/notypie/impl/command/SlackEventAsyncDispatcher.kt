package dev.notypie.impl.command

import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.domain.command.entity.event.SlackEventPayload
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async

/**
 * Async Slack-API dispatch path for domain [SlackEventPayload] events.
 *
 * Why this class exists as a separate bean:
 * - Running the outbound Slack API call synchronously on the publishing thread would
 *   extend the HTTP request's `@Transactional` scope across a network call.
 * - Attaching `@Async` directly to [ApplicationMessageDispatcher.listenSlackEvent] would
 *   require either forcing CGLIB proxies globally (`@EnableAsync(proxyTargetClass = true)`)
 *   or adding the method to the [MessageDispatcher] domain interface, which would leak a
 *   Spring event-listener concern into the domain contract.
 * - This bean implements NO interface, so Spring defaults to a CGLIB subclass proxy for
 *   it regardless of `proxyTargetClass`. The class-level `@Async` annotation is a trigger
 *   for the `kotlin-spring` all-open compiler plugin, opening the class/methods so CGLIB
 *   can subclass them without needing an explicit `open` modifier.
 *
 * This bean is wired via explicit `@Bean` in `SlackRequestBuilderConfiguration` (co-located
 * with the `MessageDispatcher` definition) rather than component-scan; `@ConditionalOnBean`
 * on scanned `@Component`s is order-sensitive per Spring Boot guidance.
 *
 * The durable delivery fallback (Outbox relay polling / CDC) is unaffected; this direct
 * path is a latency optimization for the common case where the Slack API is reachable.
 */
@Async
class SlackEventAsyncDispatcher(
    private val messageDispatcher: MessageDispatcher,
) {
    @EventListener
    fun listenSlackEvent(event: SlackEventPayload) {
        messageDispatcher.dispatch(event = event)
    }
}
