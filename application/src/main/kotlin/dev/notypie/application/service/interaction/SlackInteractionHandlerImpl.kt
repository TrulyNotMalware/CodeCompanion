package dev.notypie.application.service.interaction

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.service.mention.SlackMentionEventHandlerImpl.Companion.SLACK_APP_NAME
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.isCanceled
import dev.notypie.domain.command.dto.interactions.isPrimary
import dev.notypie.domain.command.dto.interactions.toSlackCommandData
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CompositeCommand
import dev.notypie.domain.command.entity.ReplaceTextResponseCommand
import dev.notypie.domain.history.repository.HistoryRepository
import dev.notypie.impl.command.InteractionPayloadParser
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MultiValueMap
import java.util.UUID

private val logger = KotlinLogging.logger {  }

@Service
class SlackInteractionHandlerImpl(
    private val interactionPayloadParser: InteractionPayloadParser,
    private val slackApiRequester: SlackApiRequester,
    private val applicationEventPublisher: ApplicationEventPublisher
): InteractionHandler {

    @Transactional
    override fun handleInteraction(headers: MultiValueMap<String, String>, payload: String) {
        val interactionPayload = this.interactionPayloadParser.parseStringPayload(payload = payload)
        val slackCommandData = interactionPayload.toSlackCommandData()
        val idempotencyKey = IdempotencyCreator.create(data = slackCommandData)

        if( interactionPayload.isCanceled() )
            this.rejectCommand(
                idempotencyKey = idempotencyKey, commandData = slackCommandData,
                responseUrl = interactionPayload.responseUrl
            ).handleEvent()

        else if( interactionPayload.isPrimary() ){
            val command = this.buildCommand(
                idempotencyKey = idempotencyKey,
                commandData = slackCommandData
            )
            val result = command.handleEvent()
            result.takeIf { it.ok }?.let {
                applicationEventPublisher.publishEvent(it)
            }
        }
    }

    private fun buildCommand(idempotencyKey: UUID, commandData: SlackCommandData) : Command =
        CompositeCommand(appName = SLACK_APP_NAME, idempotencyKey = idempotencyKey,
            commandData = commandData, slackApiRequester = slackApiRequester)

    private fun rejectCommand(idempotencyKey: UUID, commandData: SlackCommandData,
                              responseUrl: String): Command =
        ReplaceTextResponseCommand(idempotencyKey = idempotencyKey, commandData = commandData,
            slackApiRequester = this.slackApiRequester, markdownMessage = "Canceled.", responseUrl = responseUrl)
}