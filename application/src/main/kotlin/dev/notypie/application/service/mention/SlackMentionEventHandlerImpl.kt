package dev.notypie.application.service.mention

import com.fasterxml.jackson.databind.ObjectMapper
import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.SlackRequestHandler
import dev.notypie.domain.command.SlackRequestBuilder
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.mention.SlackAppMentionRequest
import dev.notypie.domain.command.dto.response.SlackApiResponse
import dev.notypie.domain.command.entity.Command
import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap

@Service
class SlackMentionEventHandlerImpl(
    private val objectMapper: ObjectMapper,
    private val slackRequestHandler: SlackRequestHandler,
    private val slackResponseBuilder: SlackRequestBuilder
): AppMentionEventHandler {
    companion object {
        const val SLACK_APPID_KEY_NAME = "api_app_id"
        const val SLACK_APP_NAME = "helperDev"
    }

    override fun handleEvent(headers: MultiValueMap<String, String>, payload: Map<String, Any>): SlackApiResponse {
        val slackCommandData = this.parseAppMentionEvent(headers = headers, payload = payload)
        val command = this.buildCommand(commandData = slackCommandData)
        return command.handleEvent()
    }

    override fun parseAppMentionEvent(
        headers: MultiValueMap<String, String>, payload: Map<String, Any>
    ): SlackCommandData{
        val appId = this.resolveAppId(payload = payload)
        val body = this.convertBodyData(payload = payload)
        return SlackCommandData(
            appId = appId, appToken = body.token, publisherId = body.event.userId, channel = body.event.channel,
            slackCommandType = SlackCommandType.APP_MENTION, rawHeader = SlackRequestHeaders(underlying = headers),
            rawBody = payload, body = body
        )
    }

    private fun buildCommand(commandData: SlackCommandData) : Command = Command(appName = SLACK_APP_NAME, commandData = commandData,
        slackRequestHandler = slackRequestHandler, slackResponseBuilder = slackResponseBuilder)

    override fun handleEvent(slackCommandData: SlackCommandData): SlackApiResponse = this.buildCommand(commandData = slackCommandData).handleEvent()

    private fun resolveAppId(payload: Map<String, Any>): String{
        if(payload[SLACK_APPID_KEY_NAME] != null) return payload[SLACK_APPID_KEY_NAME].toString()
        else throw RuntimeException("COMMAND_TYPE_NOT_DETECTED")
    }

    private fun convertBodyData( payload : Map<String, Any> ) = this.objectMapper.convertValue(payload, SlackAppMentionRequest::class.java)

}