package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.entity.context.CommandContext
import java.util.*

class InteractionCommandParser(
    private val slackCommandData: SlackCommandData,
    val baseUrl: String,
    val commandId: UUID,
    val idempotencyKey: String,
): ContextParser{
    private val interactionPayload = slackCommandData.body as InteractionPayload

    override fun parseContext(idempotencyKey: String): CommandContext {
        TODO()
    }

}