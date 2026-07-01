package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.InboundInteraction
import dev.notypie.domain.command.intent.IntentQueue
import java.util.*

internal class InteractionContextParser(
    private val commandData: InboundCommand,
    private val interaction: InboundInteraction,
    val baseUrl: String,
    val commandId: UUID,
    val idempotencyKey: UUID,
    private val intents: IntentQueue,
) : ContextParser {
    private val subCommand = SubCommand.empty()

    override fun parseContext(idempotencyKey: UUID): CommandContext<out SubCommandDefinition> {
        val basicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey)
        val context =
            interaction.detailType.createContext(
                commandBasicInfo = basicInfo,
                subCommand = subCommand,
                intents = intents,
            )
        return context
    }
}
