package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.*

internal class InteractionCotextParser(
    private val slackCommandData: SlackCommandData,
    val baseUrl: String,
    val commandId: UUID,
    val idempotencyKey: UUID,
    private val slackEventBuilder: SlackEventBuilder,
    private val events: EventQueue<CommandEvent<EventPayload>>,
) : ContextParser {
    private val subCommand = SubCommand.empty()
    private val interactionPayload = slackCommandData.body as InteractionPayload

    override fun parseContext(idempotencyKey: UUID): CommandContext<out SubCommandDefinition> {
        val basicInfo = slackCommandData.extractBasicInfo(idempotencyKey = idempotencyKey)
        val context =
            interactionPayload.type.createContext(
                slackEventBuilder = slackEventBuilder,
                commandBasicInfo = basicInfo,
                events = events,
                requestHeaders = slackCommandData.rawHeader,
                subCommand = subCommand,
            )
        return context
    }
}
