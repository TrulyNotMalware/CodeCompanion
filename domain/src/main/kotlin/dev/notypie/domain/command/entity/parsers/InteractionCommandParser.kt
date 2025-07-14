package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.EventQueue
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.common.event.CommandEvent
import dev.notypie.domain.common.event.EventPayload
import java.util.*

internal class InteractionCommandParser(
    private val slackCommandData: SlackCommandData,
    val baseUrl: String,
    val commandId: UUID,
    val idempotencyKey: UUID,
    private val slackEventBuilder: SlackEventBuilder,
    private val events: EventQueue<CommandEvent<EventPayload>>
): ContextParser{
    private val interactionPayload = slackCommandData.body as InteractionPayload

    override fun parseContext(
        idempotencyKey: UUID
    ): CommandContext {
        val basicInfo = slackCommandData.extractBasicInfo(idempotencyKey = idempotencyKey)
        return interactionPayload.type.createContext(
            slackEventBuilder = this.slackEventBuilder,
            commandBasicInfo = basicInfo,
            events = this.events,
            requestHeaders = slackCommandData.rawHeader
        )
    }


}