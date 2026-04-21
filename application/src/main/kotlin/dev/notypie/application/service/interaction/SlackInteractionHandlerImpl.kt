package dev.notypie.application.service.interaction

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.application.service.mention.SlackMentionEventHandlerImpl.Companion.SLACK_APP_NAME
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.interactions.isCanceled
import dev.notypie.domain.command.dto.interactions.isPrimary
import dev.notypie.domain.command.dto.interactions.toSlackCommandData
import dev.notypie.domain.command.entity.CommandDetailType
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
    companion object {
        /**
         * Legacy interaction types whose REJECT button is handled by a global "Canceled."
         * replace message at the handler level, bypassing context routing. These existed
         * before feature-specific contexts were introduced.
         *
         * New [CommandDetailType] values should NOT be added here; they should handle their
         * own REJECT button inside their dedicated [dev.notypie.domain.command.entity.context.ReactionContext]
         * (e.g. cancel-confirm modal's Cancel button, meeting participant Decline button).
         */
        internal val LEGACY_AUTO_REJECT_TYPES: Set<CommandDetailType> =
            setOf(
                CommandDetailType.REQUEST_APPLY_FORM,
                CommandDetailType.APPROVAL_FORM,
            )
    }

    @Transactional
    override fun handleInteraction(headers: MultiValueMap<String, String>, payload: String) {
        val interactionPayload = interactionPayloadParser.parseStringPayload(payload = payload)
        val slackCommandData = interactionPayload.toSlackCommandData()
        val idempotencyKey = IdempotencyCreator.create(data = slackCommandData)

        if (shouldUseLegacyReject(payload = interactionPayload)) {
            commandExecutor.execute(
                command =
                    rejectCommand(
                        idempotencyKey = idempotencyKey,
                        commandData = slackCommandData,
                        responseUrl = interactionPayload.responseUrl,
                    ),
            )
        } else if (interactionPayload.isPrimary() || interactionPayload.isCanceled()) {
            val command = buildCommand(idempotencyKey = idempotencyKey, commandData = slackCommandData)
            val result = commandExecutor.execute(command = command)
            // FIXME Event publisher
            result.takeIf { it.ok }?.let { applicationEventPublisher.publishEvent(it) }
        }
    }

    private fun shouldUseLegacyReject(payload: InteractionPayload): Boolean =
        payload.isCanceled() && payload.type in LEGACY_AUTO_REJECT_TYPES

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
