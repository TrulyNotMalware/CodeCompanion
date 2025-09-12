package dev.notypie.domain.command.entity

import dev.notypie.domain.command.NoSubCommands
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.SubCommand
import dev.notypie.domain.command.SubCommandDefinition
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.entity.context.CommandContext
import dev.notypie.domain.command.entity.context.ReplaceMessageContext
import dev.notypie.domain.common.event.EventPublisher
import java.util.UUID

class ReplaceTextResponseCommand(
    idempotencyKey: UUID,
    commandData: SlackCommandData,
    slackEventBuilder: SlackEventBuilder,
    eventPublisher: EventPublisher,
    private val markdownMessage: String,
    private val responseUrl: String,
) : Command(
        idempotencyKey = idempotencyKey,
        commandData = commandData,
        slackEventBuilder = slackEventBuilder,
        eventPublisher = eventPublisher,
    ) {
    override fun parseContext(subCommand: SubCommand): CommandContext =
        ReplaceMessageContext(
            commandBasicInfo = commandData.extractBasicInfo(idempotencyKey = idempotencyKey),
            requestHeaders = SlackRequestHeaders(),
            slackEventBuilder = slackEventBuilder,
            markdownMessage = markdownMessage,
            responseUrl = responseUrl,
            events = events,
            subCommand = subCommand,
        )

    override fun findSubCommandDefinition(): SubCommandDefinition = NoSubCommands()
}
