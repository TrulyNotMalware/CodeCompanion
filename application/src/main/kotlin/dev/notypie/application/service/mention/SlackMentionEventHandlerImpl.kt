package dev.notypie.application.service.mention

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.exception.AppIdNotFoundException
import dev.notypie.application.exception.PayloadParseErrorCode
import dev.notypie.application.service.history.HistoryHandler
import dev.notypie.common.jsonMapper
import dev.notypie.domain.command.SlackCommandType
import dev.notypie.domain.command.SlackEventBuilder
import dev.notypie.domain.command.dto.SlackCommandData
import dev.notypie.domain.command.dto.SlackRequestHeaders
import dev.notypie.domain.command.dto.mention.SlackEventCallBackRequest
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.InteractionCommand
import dev.notypie.domain.command.entity.event.EventPublisher
import dev.notypie.domain.common.error.exceptionDetails
import dev.notypie.domain.history.entity.History
import dev.notypie.domain.history.mapper.mapHistory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MultiValueMap
import java.util.UUID

@Service
class SlackMentionEventHandlerImpl(
    private val slackEventBuilder: SlackEventBuilder,
    private val historyHandler: HistoryHandler,
    private val eventPublisher: EventPublisher,
) : AppMentionEventHandler {
    companion object {
        const val SLACK_APPID_KEY_NAME = "api_app_id"
        const val SLACK_APP_NAME = "CodeCompanion"
        const val REQUEST_TYPE = "APP_MENTION"
    }

    // FIXME Remove AppMention Events.
    @Transactional
    override fun handleEvent(headers: MultiValueMap<String, String>, payload: Map<String, Any>): CommandOutput {
        val slackCommandData = parseAppMentionEvent(headers = headers, payload = payload)
        return handleEvent(slackCommandData = slackCommandData)
    }

    override fun parseAppMentionEvent(
        headers: MultiValueMap<String, String>,
        payload: Map<String, Any>,
    ): SlackCommandData {
        val appId = resolveAppId(payload = payload)
        val body = convertBodyData(payload = payload)
        val commandType = SlackCommandType.valueOf(body.type.uppercase())
        return SlackCommandData(
            appId = appId,
            appToken = body.token,
            publisherId = body.event.userId,
            channel = body.event.channel,
            slackCommandType = commandType,
            rawHeader = SlackRequestHeaders(headers = headers),
            rawBody = payload,
            body = body,
            publisherName = payload["user_name"].toString(), // FIXME
            channelName = payload["channel_name"].toString(), // FIXME
        )
    }

    private fun buildCommand(idempotencyKey: UUID, commandData: SlackCommandData) =
        InteractionCommand(
            appName = SLACK_APP_NAME,
            idempotencyKey = idempotencyKey,
            commandData = commandData,
            slackEventBuilder = slackEventBuilder,
            eventPublisher = eventPublisher,
        )

    @Transactional
    override fun handleEvent(slackCommandData: SlackCommandData): CommandOutput {
        val idempotencyKey = IdempotencyCreator.create(data = slackCommandData)
        val command = buildCommand(idempotencyKey = idempotencyKey, commandData = slackCommandData)
        val result: CommandOutput = command.handleEvent()
        val history: History = mapHistory(requestType = REQUEST_TYPE, slackApiResponse = result)
        historyHandler.saveNewHistory(history = history)
        return result
    }

    private fun resolveAppId(payload: Map<String, Any>) =
        payload[SLACK_APPID_KEY_NAME]?.toString()
            ?: throw AppIdNotFoundException(
                errorCode = PayloadParseErrorCode.APP_ID_NOT_FOUND,
                details = exceptionDetails { SLACK_APPID_KEY_NAME value "" because "Missing api_app_id in payload" },
            )

    private fun convertBodyData(payload: Map<String, Any>) =
        jsonMapper.convertValue(payload, SlackEventCallBackRequest::class.java)
}
