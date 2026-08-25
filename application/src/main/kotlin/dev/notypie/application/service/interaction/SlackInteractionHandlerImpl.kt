package dev.notypie.application.service.interaction

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.application.service.mention.SlackMentionEventHandlerImpl.Companion.SLACK_APP_NAME
import dev.notypie.common.jsonMapper
import dev.notypie.domain.command.authorization.UserRole
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.InteractionCommand
import dev.notypie.domain.command.entity.ReplaceTextResponseCommand
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.meet.entity.RejectReason
import dev.notypie.impl.command.InteractionPayloadParser
import dev.notypie.impl.command.slack.ActionElementTypes
import dev.notypie.impl.command.slack.InteractionPayload
import dev.notypie.impl.command.slack.isCanceled
import dev.notypie.impl.command.slack.isPrimary
import dev.notypie.impl.command.toInboundCommand
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
         * Legacy types whose REJECT button is handled by a global "Canceled." replace at the handler
         * level, bypassing context routing. Do NOT add new types here — new contexts handle their own
         * REJECT button inside their dedicated [dev.notypie.domain.command.entity.context.ReactionContext].
         */
        internal val LEGACY_AUTO_REJECT_TYPES: Set<CommandDetailType> =
            setOf(
                CommandDetailType.APPLY_REQUEST,
                CommandDetailType.APPROVAL_REQUEST,
            )
    }

    @Transactional
    override fun handleInteraction(headers: MultiValueMap<String, String>, payload: String): String? {
        val interactionPayload = interactionPayloadParser.parseStringPayload(payload = payload)

        // A blank "Other" detail needs a synchronous inline error and must not persist, so gate here.
        declineDetailErrorOrNull(payload = interactionPayload)?.let { return it }

        val commandData = interactionPayload.toInboundCommand()
        val idempotencyKey = IdempotencyCreator.create(data = commandData)

        if (shouldUseLegacyReject(payload = interactionPayload)) {
            commandExecutor.execute(
                command =
                    rejectCommand(
                        idempotencyKey = idempotencyKey,
                        commandData = commandData,
                        responseUrl = interactionPayload.responseUrl,
                    ),
            )
        } else if (interactionPayload.isPrimary() || interactionPayload.isCanceled()) {
            val command = buildCommand(idempotencyKey = idempotencyKey, commandData = commandData)
            val result = commandExecutor.execute(command = command)
            // FIXME Event publisher
            result.takeIf { it.ok }?.let { applicationEventPublisher.publishEvent(it) }
        }
        return null
    }

    /**
     * When the decline-reason modal picks OTHER with a blank detail, returns a `response_action: errors`
     * body so Slack shows an inline error and keeps the modal open; null otherwise.
     */
    private fun declineDetailErrorOrNull(payload: InteractionPayload): String? {
        if (payload.type != CommandDetailType.MEETING_DECLINE_REASON) return null
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

    // Interactions (modals, buttons, view submissions) are BASIC-permission flows open to every
    // role, so the default USER role is sufficient here.
    private fun buildCommand(idempotencyKey: UUID, commandData: InboundCommand): InteractionCommand =
        InteractionCommand(
            appName = SLACK_APP_NAME,
            idempotencyKey = idempotencyKey,
            commandData = commandData,
            actorRole = UserRole.USER,
        )

    private fun rejectCommand(
        idempotencyKey: UUID,
        commandData: InboundCommand,
        responseUrl: String,
    ): ReplaceTextResponseCommand =
        ReplaceTextResponseCommand(
            idempotencyKey = idempotencyKey,
            commandData = commandData,
            markdownMessage = "Canceled.",
            replyHandle = responseUrl,
        )
}
