package dev.notypie.domain.command.entity.parsers

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.EmptyContext
import dev.notypie.domain.command.entity.context.SlackApprovalFormContext
import java.util.*

class InteractionCommandParser(
    private val slackCommandData: SlackCommandData,
    val baseUrl: String,
    val commandId: UUID,
    val idempotencyKey: String,
    private val slackApiRequester: SlackApiRequester
): ContextParser{
    private val interactionPayload = slackCommandData.body as InteractionPayload

    override fun parseContext(idempotencyKey: String): CommandContext =
        when(this.interactionPayload.type){
            CommandDetailType.APPROVAL_FORM ->
                SlackApprovalFormContext(
                    slackApiRequester = this.slackApiRequester,
                    commandBasicInfo = this.slackCommandData.extractBasicInfo(idempotencyKey = this.idempotencyKey)
                ).doWhenApproved()
            else -> EmptyContext(
                commandBasicInfo = this.slackCommandData.extractBasicInfo(idempotencyKey = this.idempotencyKey),
                requestHeaders = slackCommandData.rawHeader,
                slackApiRequester = slackApiRequester
            )
        }


}