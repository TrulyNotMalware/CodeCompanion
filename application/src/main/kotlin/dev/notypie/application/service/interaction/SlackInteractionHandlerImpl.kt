package dev.notypie.application.service.interaction

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.service.mention.SlackMentionEventHandlerImpl.Companion.SLACK_APP_NAME
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.isCanceled
import dev.notypie.domain.command.dto.interactions.isPrimary
import dev.notypie.domain.command.dto.interactions.toSlackCommandData
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.command.entity.CompositeCommand
import dev.notypie.domain.command.entity.slash.ReplaceTextResponseCommand
import dev.notypie.domain.history.repository.HistoryRepository
import dev.notypie.impl.command.InteractionPayloadParser
import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap

@Service
class SlackInteractionHandlerImpl(
    private val interactionPayloadParser: InteractionPayloadParser,
    private val slackApiRequester: SlackApiRequester,
    private val historyRepository: HistoryRepository
): InteractionHandler {

    override fun handleInteraction(headers: MultiValueMap<String, String>, payload: String) {
        val interactionPayload = this.interactionPayloadParser.parseStringPayload(payload = payload)
        val slackCommandData = interactionPayload.toSlackCommandData()
        val idempotencyKey = IdempotencyCreator.create(data = slackCommandData)

        if( interactionPayload.isCanceled() )
            //TODO RETURN RESPONSES
            this.rejectCommand(
                idempotencyKey = idempotencyKey, commandData = slackCommandData,
                responseUrl = interactionPayload.responseUrl
            ).handleEvent()

        else if( interactionPayload.isPrimary() ){
            val command = this.buildCommand(
                idempotencyKey = idempotencyKey,
                commandData = slackCommandData
            )
            val slackApiResponse = command.handleEvent()
        }
    }

    private fun buildCommand(idempotencyKey: String, commandData: SlackCommandData) : Command =
        CompositeCommand(appName = SLACK_APP_NAME, idempotencyKey = idempotencyKey,
            commandData = commandData, slackApiRequester = slackApiRequester)

    private fun rejectCommand(idempotencyKey: String, commandData: SlackCommandData,
                              responseUrl: String): Command =
        ReplaceTextResponseCommand(idempotencyKey = idempotencyKey, commandData = commandData,
            slackApiRequester = this.slackApiRequester, markdownMessage = "Canceled.", responseUrl = responseUrl)
}