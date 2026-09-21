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

class CommandExecutor(
    private val intentResolver: SlackIntentResolver,
    private val outboundStager: OutboundMessageStager,
    private val eventPublisher: EventPublisher,
) {
    private val log = KotlinLogging.logger {}

    fun <T : SubCommandDefinition> execute(command: Command<T>): CommandOutput {
        val output = command.handleEvent()

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
        // Not filterIsInstance: CommandEffect isn't sealed, so a new type must fail loudly, not vanish.
        val intents = mutableListOf<CommandIntent>()
        val outbound = mutableListOf<OutboundMessage>()
        effects.forEach { effect ->
            when (effect) {
                is CommandIntent -> intents.add(effect)
                is OutboundMessage -> outbound.add(effect)
                else ->
                    error(
                        "Unclassified CommandEffect ${effect::class.qualifiedName} for " +
                            "commandId=${command.commandId} idempotencyKey=${command.idempotencyKey} — " +
                            "route the new effect type here explicitly or it would be dropped",
                    )
            }
        }

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
