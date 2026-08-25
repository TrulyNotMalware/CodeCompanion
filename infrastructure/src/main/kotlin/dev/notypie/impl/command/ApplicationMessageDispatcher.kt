package dev.notypie.impl.command

import com.slack.api.RequestConfigurator
import com.slack.api.Slack
import com.slack.api.methods.SlackApiTextResponse
import com.slack.api.methods.response.chat.ChatPostEphemeralResponse
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.methods.response.chat.ChatUpdateResponse
import com.slack.api.util.http.SlackHttpClient.buildOkHttpClient
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandDetailType
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.event.DeclineModalOpenFailedEvent
import dev.notypie.domain.command.entity.event.StandupModalOpenFailedEvent
import dev.notypie.impl.command.event.ActionEventPayloadContents
import dev.notypie.impl.command.event.MessageDispatcher
import dev.notypie.impl.command.event.MessageType
import dev.notypie.impl.command.event.OpenViewPayloadContents
import dev.notypie.impl.command.event.PostEventPayloadContents
import dev.notypie.impl.command.event.SlackEventPayload
import dev.notypie.impl.command.event.failOutput
import dev.notypie.impl.command.event.successOutput
import dev.notypie.impl.retry.RetryService
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.springframework.context.ApplicationEventPublisher

private val dispatcherLog = KotlinLogging.logger {}

class ApplicationMessageDispatcher(
    private val botToken: String,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val retryService: RetryService,
) : MessageDispatcher {
    private val slack: Slack = Slack.getInstance()
    private val okHttpClient = buildOkHttpClient(slack.config)
    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    override fun dispatch(event: SlackEventPayload): CommandOutput =
        retryService.execute(
            action = {
                when (event) {
                    is ActionEventPayloadContents -> {
                        dispatchActionResponseContents(event = event)
                    }

                    is PostEventPayloadContents -> {
                        when (event.messageType) {
                            MessageType.EPHEMERAL_MESSAGE -> dispatchEphemeralContents(event = event)
                            MessageType.CHANNEL_ALERT -> dispatchChatPostMessageContents(event = event)
                            MessageType.DIRECT_MESSAGE -> dispatchChatPostMessageContents(event = event)
                            MessageType.UPDATE_MESSAGE -> dispatchChatUpdateContents(event = event)
                            MessageType.ACTION_RESPONSE ->
                                throw IllegalStateException(
                                    "PostEventPayloadContents with ACTION_RESPONSE messageType is invalid; " +
                                        "ACTION_RESPONSE must be dispatched as ActionEventPayloadContents " +
                                        "(idempotencyKey=${event.idempotencyKey})",
                                )
                        }
                    }

                    is OpenViewPayloadContents -> {
                        throw UnsupportedOperationException(
                            "OpenViewPayloadContents cannot be dispatched via the outbox relay path — " +
                                "trigger_id expires in 3s. Use dispatchImmediate(event) on the request " +
                                "thread instead. idempotencyKey=${event.idempotencyKey}",
                        )
                    }
                }
            },
        )

    private fun dispatchEphemeralContents(event: PostEventPayloadContents) =
        dispatchPostContents(
            event,
            apiMethod = "chat.postEphemeral",
            responseType = ChatPostEphemeralResponse::class.java,
        )

    private fun dispatchChatPostMessageContents(event: PostEventPayloadContents) =
        dispatchPostContents(event, apiMethod = "chat.postMessage", responseType = ChatPostMessageResponse::class.java)

    private fun dispatchChatUpdateContents(event: PostEventPayloadContents) =
        dispatchPostContents(event, apiMethod = "chat.update", responseType = ChatUpdateResponse::class.java)

    private fun <T : SlackApiTextResponse> dispatchPostContents(
        event: PostEventPayloadContents,
        apiMethod: String,
        responseType: Class<T>,
    ): CommandOutput {
        val requestConfigurer =
            RequestConfigurator<FormBody.Builder> { builder ->
                for ((key, value) in event.body) builder.add(key, value.toString())
                builder
            }
        val result =
            slack.methods().postFormWithTokenAndParseResponse(
                requestConfigurer,
                apiMethod,
                botToken,
                responseType,
            )
        return buildCommandOutputFromResponse(result = result, event = event)
    }

    /**
     * Synchronous `views.open`, bypassing the outbox because [OpenViewPayloadContents.triggerId]
     * expires 3s after issuance. On any failure it logs and publishes the fallback open-failed event
     * rather than rethrowing, so subsequent intent dispatch from the same batch is not aborted.
     */
    override fun dispatchImmediate(event: OpenViewPayloadContents): CommandOutput {
        val response =
            runCatching {
                slack.methods(botToken).viewsOpen { builder ->
                    builder.triggerId(event.triggerId).viewAsString(event.viewJson)
                }
            }
        return response.fold(
            onSuccess = { apiResponse ->
                if (apiResponse.isOk) {
                    successOutput(payload = event, commandType = CommandType.EXTERNAL_API)
                } else {
                    dispatcherLog.warn {
                        "views.open rejected by Slack: error=${apiResponse.error} " +
                            "meetingIdempotencyKey=${event.meetingIdempotencyKey} " +
                            "participantUserId=${event.participantUserId}"
                    }
                    publishOpenFailure(event = event, reason = apiResponse.error ?: "unknown Slack error")
                    failOutput(event = event, reason = apiResponse.error ?: "views.open failed")
                }
            },
            onFailure = { error ->
                dispatcherLog.error(error) {
                    "views.open threw: meetingIdempotencyKey=${event.meetingIdempotencyKey} " +
                        "participantUserId=${event.participantUserId}"
                }
                publishOpenFailure(event = event, reason = error.message ?: error::class.java.simpleName)
                failOutput(event = event, reason = error.message ?: "views.open threw")
            },
        )
    }

    /**
     * Routes a `views.open` failure to the feature-specific fallback event. Requires
     * [OpenViewPayloadContents.participantUserId] so the listener has someone to DM; blank fires nothing.
     */
    private fun publishOpenFailure(event: OpenViewPayloadContents, reason: String) {
        if (event.participantUserId.isBlank()) return
        when (event.commandDetailType) {
            CommandDetailType.MEETING_DECLINE_REASON -> {
                val meetingIdempotencyKey = event.meetingIdempotencyKey ?: return
                applicationEventPublisher.publishEvent(
                    DeclineModalOpenFailedEvent(
                        meetingIdempotencyKey = meetingIdempotencyKey,
                        participantUserId = event.participantUserId,
                        apiAppId = event.apiAppId,
                        channel = event.channel,
                        idempotencyKey = event.idempotencyKey,
                        reason = reason,
                    ),
                )
            }
            CommandDetailType.STANDUP_PROMPT -> {
                applicationEventPublisher.publishEvent(
                    StandupModalOpenFailedEvent(
                        userId = event.participantUserId,
                        apiAppId = event.apiAppId,
                        channel = event.channel,
                        idempotencyKey = event.idempotencyKey,
                        reason = reason,
                    ),
                )
            }
            else -> Unit
        }
    }

    private fun dispatchActionResponseContents(event: ActionEventPayloadContents): CommandOutput {
        val requestBody = event.body.toRequestBody(contentType = mediaTypeJson)
        val request =
            Request
                .Builder()
                .url(event.responseUrl)
                .post(requestBody)
                .build()
        val result = okHttpClient.newCall(request).execute()
        result.close()
        return buildCommandOutputFromResponse(result = result, event = event)
    }

    private fun buildCommandOutputFromResponse(
        result: SlackApiTextResponse,
        event: SlackEventPayload,
        commandType: CommandType = CommandType.EXTERNAL_API,
    ) = if (result.isOk) {
        successOutput(
            payload = event,
            commandType = commandType,
            messageTs = (result as? ChatPostMessageResponse)?.ts.orEmpty(),
        )
    } else {
        // Surface Slack-side rejections (ok=false); otherwise the message silently never arrives.
        dispatcherLog.warn {
            "Slack rejected ${event.commandDetailType}: error=${result.error} warning=${result.warning}"
        }
        failOutput(event = event, reason = result.error)
    }

    private fun buildCommandOutputFromResponse(
        result: Response,
        event: SlackEventPayload,
        commandType: CommandType = CommandType.RESPONSE,
    ) = if (result.isSuccessful) {
        successOutput(payload = event, commandType = commandType)
    } else {
        failOutput(event = event, reason = result.message)
    }
}
