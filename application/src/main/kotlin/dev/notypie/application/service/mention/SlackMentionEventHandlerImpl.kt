package dev.notypie.application.service.mention

import dev.notypie.application.common.IdempotencyCreator
import dev.notypie.application.exception.AppIdNotFoundException
import dev.notypie.application.exception.InvalidEventPayloadException
import dev.notypie.application.exception.PayloadParseErrorCode
import dev.notypie.application.exception.UnsupportedSlackCommandTypeException
import dev.notypie.application.service.command.CommandExecutor
import dev.notypie.application.service.command.CommandRoleResolver
import dev.notypie.common.jsonMapper
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.InteractionCommand
import dev.notypie.domain.command.inbound.InboundCommand
import dev.notypie.domain.common.error.exceptionDetails
import dev.notypie.impl.command.slack.SlackEventCallBackRequest
import dev.notypie.impl.command.slack.SlackEventType
import dev.notypie.impl.command.slack.toMentionInboundCommand
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.MultiValueMap
import java.util.UUID

private val log = KotlinLogging.logger {}

@Service
class SlackMentionEventHandlerImpl(
    private val commandExecutor: CommandExecutor,
    private val commandRoleResolver: CommandRoleResolver,
    transactionManager: PlatformTransactionManager,
) : AppMentionEventHandler {
    companion object {
        const val SLACK_APPID_KEY_NAME = "api_app_id"
        const val SLACK_APP_NAME = "CodeCompanion"
    }

    private val transactionTemplate = TransactionTemplate(transactionManager)

    // FIXME Remove AppMention Events.
    override fun handleEvent(headers: MultiValueMap<String, String>, payload: Map<String, Any>): CommandOutput {
        if (isIgnoredMention(payload = payload)) {
            log.debug { "Ignoring app_mention posted by this app or without a user (workflow)." }
            return CommandOutput.empty()
        }
        val commandData = parseAppMentionEvent(headers = headers, payload = payload)
        return handleEvent(commandData = commandData)
    }

    private fun isIgnoredMention(payload: Map<String, Any>): Boolean {
        val event = payload["event"] as? Map<*, *> ?: return false
        if ((event["user"] as? String).isNullOrBlank()) return true
        val ownAppId = payload[SLACK_APPID_KEY_NAME] as? String ?: return false
        val botProfile = event["bot_profile"] as? Map<*, *>
        return event["app_id"] == ownAppId || botProfile?.get("app_id") == ownAppId
    }

    override fun parseAppMentionEvent(
        headers: MultiValueMap<String, String>,
        payload: Map<String, Any>,
    ): InboundCommand {
        val appId = resolveAppId(payload = payload)
        val body = convertBodyData(payload = payload)
        resolveCommandType(rawType = body.type)
        return body.toMentionInboundCommand(
            appId = appId,
            channelName = payload["channel_name"] as? String ?: "",
            actorName = payload["user_name"] as? String ?: "",
        )
    }

    private fun buildCommand(idempotencyKey: UUID, commandData: InboundCommand) =
        InteractionCommand(
            appName = SLACK_APP_NAME,
            idempotencyKey = idempotencyKey,
            commandData = commandData,
            actorRole = commandRoleResolver.resolve(userId = commandData.actorId),
        )

    override fun handleEvent(commandData: InboundCommand): CommandOutput {
        val idempotencyKey = IdempotencyCreator.create(data = commandData)
        val command = buildCommand(idempotencyKey = idempotencyKey, commandData = commandData)
        return checkNotNull(transactionTemplate.execute { commandExecutor.execute(command = command) })
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

    private fun convertBodyData(payload: Map<String, Any>): SlackEventCallBackRequest =
        runCatching { jsonMapper.convertValue(payload, SlackEventCallBackRequest::class.java) }
            .getOrElse { cause ->
                throw InvalidEventPayloadException(
                    errorCode = PayloadParseErrorCode.INVALID_EVENT_PAYLOAD,
                    details =
                        exceptionDetails {
                            "event" value "" because
                                (cause.message ?: cause::class.java.simpleName)
                        },
                    cause = cause,
                )
            }
}
