package dev.notypie.application.service.mention

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.exception.AppIdNotFoundException
import dev.notypie.application.exception.PayloadParseErrorCode
import dev.notypie.application.exception.UnsupportedSlackCommandTypeException
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.common.jsonMapper
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.InteractionCommand
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.common.error.exceptionDetails
import dev.notypie.impl.command.slack.SlackEventCallBackRequest
import dev.notypie.impl.command.slack.SlackEventType
import dev.notypie.impl.command.slack.toMentionInboundCommand
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MultiValueMap
import java.util.UUID

@Service
class SlackMentionEventHandlerImpl(
    private val commandExecutor: CommandExecutor,
) : AppMentionEventHandler {
    companion object {
        const val SLACK_APPID_KEY_NAME = "api_app_id"
        const val SLACK_APP_NAME = "CodeCompanion"
    }

    // FIXME Remove AppMention Events.
    @Transactional
    override fun handleEvent(headers: MultiValueMap<String, String>, payload: Map<String, Any>): CommandOutput {
        val commandData = parseAppMentionEvent(headers = headers, payload = payload)
        return handleEvent(commandData = commandData)
    }

    override fun parseAppMentionEvent(
        headers: MultiValueMap<String, String>,
        payload: Map<String, Any>,
    ): InboundCommand {
        val appId = resolveAppId(payload = payload)
        val body = convertBodyData(payload = payload)
        // Validate the transport event type, preserving the reject for unknown Slack event types.
        resolveCommandType(rawType = body.type)
        return body.toMentionInboundCommand(
            appId = appId,
            channelName = payload["channel_name"].toString(), // FIXME
            actorName = payload["user_name"].toString(), // FIXME
        )
    }

    private fun buildCommand(idempotencyKey: UUID, commandData: InboundCommand) =
        InteractionCommand(
            appName = SLACK_APP_NAME,
            idempotencyKey = idempotencyKey,
            commandData = commandData,
        )

    @Transactional
    override fun handleEvent(commandData: InboundCommand): CommandOutput {
        val idempotencyKey = IdempotencyCreator.create(data = commandData)
        val command = buildCommand(idempotencyKey = idempotencyKey, commandData = commandData)
        return commandExecutor.execute(command = command)
    }

    private fun resolveAppId(payload: Map<String, Any>) =
        payload[SLACK_APPID_KEY_NAME]?.toString()
            ?: throw AppIdNotFoundException(
                errorCode = PayloadParseErrorCode.APP_ID_NOT_FOUND,
                details = exceptionDetails { SLACK_APPID_KEY_NAME value "" because "Missing api_app_id in payload" },
            )

    private fun resolveCommandType(rawType: String): SlackEventType =
        runCatching { SlackEventType.valueOf(rawType.uppercase()) }
            .getOrElse { cause ->
                throw UnsupportedSlackCommandTypeException(
                    rawCommandType = rawType,
                    errorCode = PayloadParseErrorCode.UNSUPPORTED_SLACK_COMMAND_TYPE,
                    details =
                        exceptionDetails {
                            "type" value rawType because
                                "Unknown SlackEventType value: ${cause.message}"
                        },
                )
            }

    private fun convertBodyData(payload: Map<String, Any>) =
        jsonMapper.convertValue(payload, SlackEventCallBackRequest::class.java)
}
