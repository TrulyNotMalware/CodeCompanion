package dev.notypie.application.service.command

import dev.notypie.domain.command.DefaultEventQueue
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.event.CommandEvent
import dev.notypie.domain.command.entity.event.EventPayload
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.command.intent.CommandEffect
import dev.notypie.domain.command.intent.CommandIntent
import dev.notypie.domain.command.outbound.OutboundMessage
import dev.notypie.domain.command.outbound.OutboundMessageStager
import dev.notypie.impl.command.SlackIntentResolver
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Orchestrates Command execution: drains accumulated effects, resolves them to transport-layer
 * events, and dispatches them.
 *
 * Failures are logged and re-thrown for transactional rollback at the caller. Intents are NOT
 * re-queued: publishers dispatch sequentially, so a retry could duplicate publishes — retries
 * must happen upstream (outbox relay, Kafka retries, Slack replay) under the shared idempotencyKey.
 */
class CommandExecutor(
    private val intentResolver: SlackIntentResolver,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
) {
    private val log = KotlinLogging.logger {}

    fun <T : SubCommandDefinition> execute(command: Command<T>): CommandOutput {
        val output = command.handleEvent()

        // Drain regardless of success/failure: error effects must also reach Slack.
        val pendingEffects = command.drainIntents()
        if (pendingEffects.isNotEmpty()) {
            publishIntents(
                effects = pendingEffects,
                command = command,
            )
        }

        return output
    }

    private fun <T : SubCommandDefinition> publishIntents(effects: List<CommandEffect>, command: Command<T>) {
        val intents = effects.filterIsInstance<CommandIntent>()
        val outbound = effects.filterIsInstance<OutboundMessage>()

        val basicInfo =
            command.commandData.extractBasicInfo(
                idempotencyKey = command.idempotencyKey,
            )

        val resolvedEvents =
            try {
                intentResolver.resolveAll(
                    intents = intents,
                    basicInfo = basicInfo,
                ) +
                    outbound.mapNotNull { message ->
                        outboundStager.stage(
                            message = message,
                            basicInfo = basicInfo,
                        )
                    }
            } catch (e: Exception) {
                log.error(e) {
                    "Intent resolution failed for commandId=${command.commandId} " +
                        "idempotencyKey=${command.idempotencyKey} intentCount=${intents.size} " +
                        "outboundCount=${outbound.size}"
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
