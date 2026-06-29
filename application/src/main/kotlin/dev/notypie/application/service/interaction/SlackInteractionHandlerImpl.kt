package dev.notypie.application.service.interaction

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.application.service.mention.SlackMentionEventHandlerImpl.Companion.SLACK_APP_NAME
import dev.notypie.common.jsonMapper
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.ActionElementTypes
import dev.notypie.domain.command.dto.interactions.InteractionPayload
import dev.notypie.domain.command.dto.interactions.RejectReason
import dev.notypie.domain.command.dto.interactions.isCanceled
import dev.notypie.domain.command.dto.interactions.isPrimary
import dev.notypie.domain.command.dto.interactions.toSlackCommandData
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.InteractionCommand
import dev.notypie.domain.command.entity.ReplaceTextResponseCommand
import dev.notypie.impl.command.InteractionPayloadParser
import dev.notypie.templates.DeclineReasonModalIds
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
    override fun handleInteraction(headers: MultiValueMap<String, String>, payload: String): String? {
        val interactionPayload = interactionPayloadParser.parseStringPayload(payload = payload)

        // A blank "Other" detail must be answered synchronously with an inline error and must NOT
        // persist the decline, so this gate runs before any command execution.
        declineDetailErrorOrNull(payload = interactionPayload)?.let { return it }

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
        return null
    }

    /**
     * view_submission validation for the decline-reason modal: when the user picked OTHER but left
     * the detail blank, returns a `response_action: errors` body (keyed by the detail block) so
     * Slack shows an inline error and keeps the modal open. Returns null for every other case,
     * letting the submission proceed to persistence.
     */
    private fun declineDetailErrorOrNull(payload: InteractionPayload): String? {
        if (payload.type != CommandDetailType.DECLINE_REASON_MODAL) return null
        val selectedReason =
            payload.states
                .firstOrNull { it.type == ActionElementTypes.STATIC_SELECT }
                ?.selectedValue
                .orEmpty()
        val isOther = runCatching { RejectReason.valueOf(selectedReason) }.getOrNull() == RejectReason.OTHER
        if (!isOther) return null
        val detail =
            payload.states
                .firstOrNull { it.type == ActionElementTypes.PLAIN_TEXT_INPUT }
                ?.selectedValue
                ?.trim()
                .orEmpty()
        if (detail.isNotBlank()) return null
        return jsonMapper.writeValueAsString(
            mapOf(
                "response_action" to "errors",
                "errors" to
                    mapOf(
                        DeclineReasonModalIds.DETAIL_BLOCK_ID to
                            "Please describe your reason when selecting Other.",
                    ),
            ),
        )
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
