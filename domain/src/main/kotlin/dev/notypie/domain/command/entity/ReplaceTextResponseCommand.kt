package dev.notypie.domain.command.entity

import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.ReplaceMessageContext
import dev.notypie.domain.common.event.EventPublisher
import java.util.UUID

class ReplaceTextResponseCommand(
    idempotencyKey: UUID,
    commandData: SlackCommandData,
    slackApiRequester: SlackApiRequester,
    eventPublisher: EventPublisher,
    private val markdownMessage: String,
    private val responseUrl: String,
): Command(
    idempotencyKey = idempotencyKey,
    commandData = commandData,
    slackApiRequester = slackApiRequester,
    eventPublisher = eventPublisher
) {
    override fun parseContext(): CommandContext = ReplaceMessageContext(
        commandBasicInfo = this.commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
        requestHeaders = SlackRequestHeaders(),
        slackApiRequester = this.slackApiRequester, markdownMessage = markdownMessage,
        responseUrl = responseUrl, events = this.events
    )
}