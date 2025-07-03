package dev.notypie.domain.command.entity

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import dev.notypie.domain.common.event.EventPublisher
import java.util.*

//Aggregate Root
abstract class Command(
    val idempotencyKey: UUID,
    val commandData: SlackCommandData,
    internal val slackApiRequester: SlackApiRequester,
    internal val eventPublisher: EventPublisher,
    internal val events: Queue<CommandEvent<EventPayload>> = LinkedList()
) {
    val commandId = this.generateIdValue()
    internal abstract fun parseContext(): CommandContext

    fun handleEvent() = runCatching { executeCommand() }
        .onSuccess { publishEvents() }
        .getOrElse { exception ->
        CommandOutput.fail(
            slackCommandData = this.commandData, idempotencyKey = this.idempotencyKey,
            commandDetailType = CommandDetailType.ERROR_RESPONSE,
            reason = exception.toString()
        )
    }

    private fun publishEvents() = this.eventPublisher.publishEvent(events = this.events)


    internal open fun executeCommand(): CommandOutput{
        val context = this.parseContext()
        return if( commandData.slackCommandType == SlackCommandType.INTERACTION_RESPONSE )
            context.handleInteraction(this.commandData.body as InteractionPayload)
        else context.runCommand()
    }

    private fun generateIdValue(): UUID = UUID.randomUUID()
}