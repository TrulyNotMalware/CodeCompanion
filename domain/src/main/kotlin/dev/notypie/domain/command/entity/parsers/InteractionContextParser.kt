package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.intent.IntentQueue
import java.util.*

internal class InteractionContextParser(
    private val slackCommandData: SlackCommandData,
    val baseUrl: String,
    val commandId: UUID,
    val idempotencyKey: UUID,
    private val intents: IntentQueue,
) : ContextParser {
    private val subCommand = SubCommand.empty()
    private val interactionPayload = slackCommandData.body as InteractionPayload

    override fun parseContext(idempotencyKey: UUID): CommandContext<out SubCommandDefinition> {
        val basicInfo = slackCommandData.extractBasicInfo(idempotencyKey = idempotencyKey)
        val context =
            interactionPayload.type.createContext(
                commandBasicInfo = basicInfo,
                requestHeaders = slackCommandData.rawHeader,
                subCommand = subCommand,
                intents = intents,
            )
        return context
    }
}
