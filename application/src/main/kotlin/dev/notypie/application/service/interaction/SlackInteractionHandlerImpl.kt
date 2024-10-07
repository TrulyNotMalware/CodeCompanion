package dev.notypie.application.service.interaction

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.service.mention.SlackMentionEventHandlerImpl.Companion.SLACK_APP_NAME
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.interactions.isCompleted
import dev.notypie.domain.command.dto.interactions.toSlackCommandData
import dev.notypie.domain.command.entity.Command
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

    override fun handleInteractions(headers: MultiValueMap<String, String>, payload: String) {
        val interactionPayload = this.interactionPayloadParser.parseStringContents(payload = payload)
        if( interactionPayload.isCompleted() ){
            val slackCommandData = interactionPayload.toSlackCommandData()
            val idempotencyKey = IdempotencyCreator.create(data = slackCommandData)
            val command = this.buildCommand(
                idempotencyKey = idempotencyKey,
                commandData = slackCommandData
            )
            val slackApiResponse = command.handleEvent()

        }
    }


    private fun buildCommand(idempotencyKey: String, commandData: SlackCommandData) : Command =
        Command(appName = SLACK_APP_NAME, idempotencyKey = idempotencyKey,
            commandData = commandData, slackApiRequester = slackApiRequester)
}