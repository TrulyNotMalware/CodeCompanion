package dev.notypie.application.service.interaction

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.application.service.meeting.MeetingWriteDeferral
import dev.notypie.application.service.mention.SlackMentionEventHandlerImpl.Companion.SLACK_APP_NAME
import dev.notypie.common.jsonMapper
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.InteractionCommand
import dev.notypie.domain.command.entity.ReplaceTextResponseCommand
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.command.inbound.SubmissionParseObserver
import dev.notypie.domain.meet.entity.RejectReason
import dev.notypie.impl.command.InteractionPayloadParser
import dev.notypie.impl.command.ViewOpenDeferral
import dev.notypie.impl.command.slack.ActionElementTypes
import dev.notypie.impl.command.slack.InteractionPayload
import dev.notypie.impl.command.slack.isCanceled
import dev.notypie.impl.command.slack.isPrimary
import dev.notypie.impl.command.toInboundCommand
import dev.notypie.templates.DeclineReasonModalIds
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.util.MultiValueMap
import java.util.UUID

@Service
class SlackInteractionHandlerImpl(
    private val interactionPayloadParser: InteractionPayloadParser,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val commandExecutor: CommandExecutor,
    private val submissionParseObserver: SubmissionParseObserver,
    private val commandRoleResolver: CommandRoleResolver,
    private val transactionManager: PlatformTransactionManager,
) : InteractionHandler {
    companion object {
        internal val LEGACY_AUTO_REJECT_TYPES: Set<CommandDetailType> =
            setOf(
                CommandDetailType.APPLY_REQUEST,
                CommandDetailType.APPROVAL_REQUEST,
            )

        private val INTERACTION_TRANSACTION =
            DefaultTransactionDefinition().apply { setName("SlackInteractionHandlerImpl.handleInteraction") }
    }

    override fun handleInteraction(headers: MultiValueMap<String, String>, payload: String): String? {
        val interactionPayload = interactionPayloadParser.parseStringPayload(payload = payload)

        declineDetailErrorOrNull(payload = interactionPayload)?.let { return it }

        val command = commandFor(interactionPayload = interactionPayload) ?: return null
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            inInteractionTransaction { execute(command = command) }
            return null
        }
        val (_, meetingWrites) =
            MeetingWriteDeferral.collecting {
                ViewOpenDeferral.afterBoundary { inInteractionTransaction { execute(command = command) } }
            }
        meetingWrites.forEach { it() }
        return null
    }

    private fun <T> inInteractionTransaction(block: () -> T): T {
        val status = transactionManager.getTransaction(INTERACTION_TRANSACTION)
        val result =
            try {
                block()
            } catch (failure: Throwable) {
                try {
                    transactionManager.rollback(status)
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
                throw failure
            }
        transactionManager.commit(status)
        return result
    }

    private fun commandFor(interactionPayload: InteractionPayload): Command<*>? {
        val commandData = interactionPayload.toInboundCommand()
        val idempotencyKey = IdempotencyCreator.create(data = commandData)
        return when {
            shouldUseLegacyReject(payload = interactionPayload) ->
                rejectCommand(
                    idempotencyKey = idempotencyKey,
                    commandData = commandData,
                    responseUrl = interactionPayload.responseUrl,
                )

            interactionPayload.isPrimary() || interactionPayload.isCanceled() ->
                buildCommand(idempotencyKey = idempotencyKey, commandData = commandData)

            else -> null
        }
    }

    private fun execute(command: Command<*>) {
        val result = commandExecutor.execute(command = command)
        // FIXME Event publisher
        if (command is InteractionCommand) result.takeIf { it.ok }?.let { applicationEventPublisher.publishEvent(it) }
    }

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

    private fun buildCommand(idempotencyKey: UUID, commandData: InboundCommand): InteractionCommand =
        InteractionCommand(
            appName = SLACK_APP_NAME,
            idempotencyKey = idempotencyKey,
            commandData = commandData,
            actorRole = commandRoleResolver.resolve(userId = commandData.actorId),
            parseObserver = submissionParseObserver,
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
