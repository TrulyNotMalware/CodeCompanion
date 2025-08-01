package dev.notypie.domain.command.entity

import dev.notypie.domain.command.DefaultEventQueue
import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import dev.notypie.domain.common.event.EventPublisher
import java.util.UUID

//Aggregate Root
abstract class Command(
    val idempotencyKey: UUID,
    val commandData: SlackCommandData,
    internal val slackEventBuilder: SlackEventBuilder,
    internal val eventPublisher: EventPublisher,
) {
    internal val events: EventQueue<CommandEvent<EventPayload>> = DefaultEventQueue()

    val commandId = this.generateIdValue()
    internal abstract fun parseContext(subCommand: SubCommand): CommandContext
    internal abstract fun findSubCommandDefinition(): SubCommandDefinition

    private fun createSubCommand(): SubCommand {
        val definition = this.findSubCommandDefinition()
        val options = this.commandData.subCommands.drop(1)
        return SubCommand(
            subCommandDefinition = definition,
            options = options
        )
    }


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


    internal fun executeCommand(): CommandOutput{
        val subCommand = this.createSubCommand()
        val context = this.parseContext(subCommand = subCommand)
        return if( commandData.slackCommandType == SlackCommandType.INTERACTION_RESPONSE )
            context.handleInteraction(this.commandData.body as InteractionPayload)
        else context.runCommand()
    }

    private fun generateIdValue(): UUID = UUID.randomUUID()
}