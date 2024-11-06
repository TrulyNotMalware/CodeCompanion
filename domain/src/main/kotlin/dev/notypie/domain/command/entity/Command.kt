package dev.notypie.domain.command.entity

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.context.CommandContext
import java.util.*

//Aggregate Root
abstract class Command(
    val idempotencyKey: String,
    val commandData: SlackCommandData,
    val slackApiRequester: SlackApiRequester,
) {
    val commandId = this.generateIdValue()
    internal abstract fun parseContext(): CommandContext

    open fun handleEvent(): CommandOutput{
        val context = this.parseContext()
        return if( commandData.slackCommandType == SlackCommandType.INTERACTION_RESPONSE )
            context.handleInteraction(this.commandData.body as InteractionPayload)
        else context.runCommand()
    }

    private fun generateIdValue(): UUID = UUID.randomUUID()
}