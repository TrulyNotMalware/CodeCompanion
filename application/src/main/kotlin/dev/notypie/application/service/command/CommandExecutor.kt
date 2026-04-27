package dev.notypie.application.service.command

import dev.notypie.domain.command.DefaultEventQueue
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.impl.command.SlackIntentResolver
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Orchestrates Command execution with Intent resolution.
 *
 * Flow:
 *  1. command.handleEvent() runs the context pipeline and accumulates CommandIntents.
 *  2. drainIntents() atomically snapshots and clears the queue.
 *  3. SlackIntentResolver maps intents to transport-layer events.
 *  4. EventPublisher dispatches the events (Spring ApplicationEvent / Kafka / Outbox).
 *
 * Failure semantics:
 *  - Resolver / publisher failures are logged with idempotency context and re-thrown so that
 *    transactional rollback can happen at the caller.
 *  - Intents are NOT re-queued on failure. The current publishers ([AppEventPublisher],
 *    [KafkaEventPublisher]) dispatch events sequentially, so a partial failure could otherwise
 *    cause duplicate publishes on retry. Retries must happen upstream (outbox relay, Kafka
 *    producer retries, or a replay of the original Slack event) with the shared idempotencyKey.
 */
class CommandExecutor(
    private val intentResolver: SlackIntentResolver,
    private val eventPublisher: EventPublisher,
) {
    private val log = KotlinLogging.logger {}

    fun <T : SubCommandDefinition> execute(command: Command<T>): CommandOutput {
        val output = command.handleEvent()

        // Drain and resolve intents regardless of success/failure.
        // Error intents (e.g. EphemeralResponse from createErrorResponse) must also reach Slack.
        val pendingIntents = command.drainIntents()
        if (pendingIntents.isNotEmpty()) {
            publishIntents(
                intents = pendingIntents,
                command = command,
            )
        }

        return output
    }

    private fun <T : SubCommandDefinition> publishIntents(intents: List<CommandIntent>, command: Command<T>) {
        val basicInfo =
            command.commandData.extractBasicInfo(
                idempotencyKey = command.idempotencyKey,
            )

        val resolvedEvents =
            try {
                intentResolver.resolveAll(
                    intents = intents,
                    basicInfo = basicInfo,
                )
            } catch (e: Exception) {
                log.error(e) {
                    "Intent resolution failed for commandId=${command.commandId} " +
                        "idempotencyKey=${command.idempotencyKey} intentCount=${intents.size}"
                }
                throw e
            }

        if (resolvedEvents.isEmpty()) return

        val eventQueue = DefaultEventQueue<CommandEvent<EventPayload>>()
        resolvedEvents.forEach { event -> eventQueue.offer(event) }
        try {
            eventPublisher.publishEvent(events = eventQueue)
        } catch (e: Exception) {
            log.error(e) {
                "Event publishing failed for commandId=${command.commandId} " +
                    "idempotencyKey=${command.idempotencyKey} eventCount=${resolvedEvents.size}. " +
                    "Partial publication may have occurred; retries must rely on upstream idempotency."
            }
            throw e
        }
    }
}
