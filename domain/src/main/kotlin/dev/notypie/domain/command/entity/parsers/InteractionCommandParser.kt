package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.SlackApiRequester
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
    private val slackApiRequester: SlackApiRequester,
    private val events: Queue<CommandEvent<EventPayload>>
): ContextParser{
    private val interactionPayload = slackCommandData.body as InteractionPayload

    override fun parseContext(
        events: Queue<CommandEvent<EventPayload>>,
        idempotencyKey: UUID
    ): CommandContext {
        val basicInfo = slackCommandData.extractBasicInfo(idempotencyKey = idempotencyKey)
        return interactionPayload.type.createContext(
            slackApiRequester = this.slackApiRequester,
            commandBasicInfo = basicInfo,
            events = events,
            requestHeaders = slackCommandData.rawHeader
        )
    }


}