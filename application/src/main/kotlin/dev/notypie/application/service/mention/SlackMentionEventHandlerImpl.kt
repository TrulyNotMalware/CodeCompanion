package dev.notypie.application.service.mention

import com.fasterxml.jackson.databind.ObjectMapper
import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.service.history.HistoryHandler
import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.SlackApiRequester
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.mention.SlackEventCallBackRequest
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.Command
import dev.notypie.domain.history.entity.History
import dev.notypie.domain.history.mapper.mapHistory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MultiValueMap

@Service
class SlackMentionEventHandlerImpl(
    private val objectMapper: ObjectMapper,
    private val slackApiRequester: SlackApiRequester,
    private val historyHandler: HistoryHandler
): AppMentionEventHandler {
    companion object {
        const val SLACK_APPID_KEY_NAME = "api_app_id"
        const val SLACK_APP_NAME = "helperDev"
        const val REQUEST_TYPE = "APP_MENTION"
    }

    @Transactional
    override fun handleEvent(headers: MultiValueMap<String, String>, payload: Map<String, Any>): SlackApiResponse {
        val slackCommandData = this.parseAppMentionEvent(headers = headers, payload = payload)
        return this.handleEvent(slackCommandData = slackCommandData)
    }

    override fun parseAppMentionEvent(
        headers: MultiValueMap<String, String>, payload: Map<String, Any>
    ): SlackCommandData{
        val appId = this.resolveAppId(payload = payload)
        val body = this.convertBodyData(payload = payload)
        val commandType = SlackCommandType.valueOf(body.type.uppercase())
        return SlackCommandData(
            appId = appId, appToken = body.token, publisherId = body.event.userId, channel = body.event.channel,
            slackCommandType = commandType, rawHeader = SlackRequestHeaders(underlying = headers),
            rawBody = payload, body = body
        )
    }

    private fun buildCommand(idempotencyKey: String, commandData: SlackCommandData) : Command =
        Command(appName = SLACK_APP_NAME, idempotencyKey = idempotencyKey,
            commandData = commandData, slackApiRequester = slackApiRequester)

    @Transactional
    override fun handleEvent(slackCommandData: SlackCommandData): SlackApiResponse{
        val idempotencyKey = IdempotencyCreator.create(data = slackCommandData)
        val command = this.buildCommand(idempotencyKey = idempotencyKey,commandData = slackCommandData)
        val result: SlackApiResponse = command.handleEvent()
        val history: History = mapHistory(requestType = REQUEST_TYPE, slackApiResponse = result)
        this.historyHandler.saveNewHistory(history = history)
        return result
    }

    private fun resolveAppId(payload: Map<String, Any>): String{
        if(payload[SLACK_APPID_KEY_NAME] != null) return payload[SLACK_APPID_KEY_NAME].toString()
        else throw RuntimeException("COMMAND_TYPE_NOT_DETECTED")
    }

    private fun convertBodyData( payload : Map<String, Any> ) = this.objectMapper.convertValue(payload, SlackEventCallBackRequest::class.java)

}