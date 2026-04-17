package dev.notypie.application.service.interaction

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.application.service.mention.SlackMentionEventHandlerImpl.Companion.SLACK_APP_NAME
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.isCanceled
import dev.notypie.domain.command.dto.interactions.isPrimary
import dev.notypie.domain.command.dto.interactions.toSlackCommandData
import dev.notypie.domain.command.entity.InteractionCommand
import dev.notypie.domain.command.entity.ReplaceTextResponseCommand
import dev.notypie.impl.command.InteractionPayloadParser
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MultiValueMap
import java.util.UUID

@Service
class SlackInteractionHandlerImpl(
    private val interactionPayloadParser: InteractionPayloadParser,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val commandExecutor: CommandExecutor,
) : InteractionHandler {
    @Transactional
    override fun handleInteraction(headers: MultiValueMap<String, String>, payload: String) {
        val interactionPayload = interactionPayloadParser.parseStringPayload(payload = payload)
        val slackCommandData = interactionPayload.toSlackCommandData()
        val idempotencyKey = IdempotencyCreator.create(data = slackCommandData)

        if (interactionPayload.isCanceled()) {
            commandExecutor.execute(
                command =
                    rejectCommand(
                        idempotencyKey = idempotencyKey,
                        commandData = slackCommandData,
                        responseUrl = interactionPayload.responseUrl,
                    ),
            )
        } else if (interactionPayload.isPrimary()) {
            val command = buildCommand(idempotencyKey = idempotencyKey, commandData = slackCommandData)
            val result = commandExecutor.execute(command = command)
            // FIXME Event publisher
            result.takeIf { it.ok }?.let { applicationEventPublisher.publishEvent(it) }
        }
    }

    private fun buildCommand(idempotencyKey: UUID, commandData: SlackCommandData): InteractionCommand =
        InteractionCommand(
            appName = SLACK_APP_NAME,
            idempotencyKey = idempotencyKey,
            commandData = commandData,
        )

    private fun rejectCommand(
        idempotencyKey: UUID,
        commandData: SlackCommandData,
        responseUrl: String,
    ): ReplaceTextResponseCommand =
        ReplaceTextResponseCommand(
            idempotencyKey = idempotencyKey,
            commandData = commandData,
            markdownMessage = "Canceled.",
            responseUrl = responseUrl,
        )
}
