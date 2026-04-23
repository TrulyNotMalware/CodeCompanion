package dev.notypie.impl.command

import com.slack.api.RequestConfigurator
import com.slack.api.Slack
import com.slack.api.methods.SlackApiTextResponse
import com.slack.api.methods.response.chat.ChatPostEphemeralResponse
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.methods.response.chat.ChatUpdateResponse
import com.slack.api.util.http.SlackHttpClient.buildOkHttpClient
import dev.notypie.domain.command.MessageDispatcher
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.entity.CommandType
import dev.notypie.domain.command.entity.event.ActionEventPayloadContents
import dev.notypie.domain.command.entity.event.DeclineModalOpenFailedEvent
import dev.notypie.domain.command.entity.event.DelayHandleEventPayloadContents
import dev.notypie.domain.command.entity.event.MessageType
import dev.notypie.domain.command.entity.event.OpenViewPayloadContents
import dev.notypie.domain.command.entity.event.PostEventPayloadContents
import dev.notypie.domain.command.entity.event.SlackEventPayload
import dev.notypie.domain.history.entity.Status
import dev.notypie.impl.retry.RetryService
import dev.notypie.repository.outbox.MessageOutboxRepository
import dev.notypie.repository.outbox.schema.toOutboxMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Instant

private val dispatcherLog = KotlinLogging.logger {}

class ApplicationMessageDispatcher(
    private val botToken: String,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val taskScheduler: ThreadPoolTaskScheduler,
    private val retryService: RetryService,
    private val outboxRepository: MessageOutboxRepository,
) : MessageDispatcher {
    private val slack: Slack = Slack.getInstance()
    private val okHttpClient = buildOkHttpClient(slack.config)
    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    override fun dispatch(event: PostEventPayloadContents, commandType: CommandType): CommandOutput {
        retryService.execute(
            action = { outboxRepository.save(event.toOutboxMessage().outboxMessage) },
            maxAttempts = 3,
        )
        return event.toCommandOutput(commandType = commandType)
    }

    override fun dispatch(event: ActionEventPayloadContents, commandType: CommandType): CommandOutput {
        retryService.execute(
            action = { outboxRepository.save(event.toOutboxMessage().outboxMessage) },
            maxAttempts = 3,
        )
        return event.toCommandOutput(commandType = commandType)
    }

    @Deprecated("for removal")
    override fun dispatch(event: DelayHandleEventPayloadContents, commandType: CommandType): CommandOutput {
        taskScheduler.schedule({
            applicationEventPublisher.publishEvent(event)
        }, Instant.now().plus(event.delayTime, event.timeUnit))
        return event.toCommandOutput(commandType = commandType)
    }

    private fun SlackEventPayload.toCommandOutput(commandType: CommandType) =
        CommandOutput(
            ok = true,
            apiAppId = apiAppId,
            status = Status.IN_PROGRESSED,
            idempotencyKey = idempotencyKey,
            publisherId = publisherId,
            channel = channel,
            commandType = commandType,
            commandDetailType = commandDetailType,
        )

    override fun dispatch(event: SlackEventPayload): CommandOutput =
        retryService.execute(
            action = {
                when (event) {
                    is ActionEventPayloadContents -> {
                        dispatchActionResponseContents(event = event)
                    }

                    is DelayHandleEventPayloadContents -> {
                        throw UnsupportedOperationException(
                            "DelayHandleEventPayloadContents dispatch is not supported via the outbox relay path " +
                                "(idempotencyKey=${event.idempotencyKey})",
                        )
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
        return returnSuccessOrFailed(result = result, event = event)
    }

    /**
     * Synchronous `views.open` call. Bypasses the outbox because [OpenViewPayloadContents.triggerId]
     * expires 3 seconds after issuance — staging the call for later relay would always race the
     * expiry. On any failure (API error, network, malformed view JSON, expired trigger) we log
     * and publish [DeclineModalOpenFailedEvent] so the application layer can persist the decline
     * with RejectReason.OTHER. We intentionally do NOT rethrow: the caller listener runs inside
     * the request thread and throwing would abort any subsequent intent dispatch from the same
     * batch, which is not what the user's Deny click should trigger.
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
                    CommandOutput.success(payload = event, commandType = CommandType.EXTERNAL_API)
                } else {
                    dispatcherLog.warn {
                        "views.open rejected by Slack: error=${apiResponse.error} " +
                            "meetingIdempotencyKey=${event.meetingIdempotencyKey} " +
                            "participantUserId=${event.participantUserId}"
                    }
                    publishOpenFailure(event = event, reason = apiResponse.error ?: "unknown Slack error")
                    CommandOutput.fail(event = event, reason = apiResponse.error ?: "views.open failed")
                }
            },
            onFailure = { error ->
                dispatcherLog.error(error) {
                    "views.open threw: meetingIdempotencyKey=${event.meetingIdempotencyKey} " +
                        "participantUserId=${event.participantUserId}"
                }
                publishOpenFailure(event = event, reason = error.message ?: error::class.java.simpleName)
                CommandOutput.fail(event = event, reason = error.message ?: "views.open threw")
            },
        )
    }

    private fun publishOpenFailure(event: OpenViewPayloadContents, reason: String) {
        applicationEventPublisher.publishEvent(
            DeclineModalOpenFailedEvent(
                meetingIdempotencyKey = event.meetingIdempotencyKey,
                participantUserId = event.participantUserId,
                apiAppId = event.apiAppId,
                channel = event.channel,
                idempotencyKey = event.idempotencyKey,
                reason = reason,
            ),
        )
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
        return returnSuccessOrFailed(result = result, event = event)
    }

    private fun returnSuccessOrFailed(
        result: SlackApiTextResponse,
        event: SlackEventPayload,
        commandType: CommandType = CommandType.EXTERNAL_API,
    ) = if (result.isOk) {
        CommandOutput.success(payload = event, commandType = commandType)
    } else {
        CommandOutput.fail(event = event, reason = result.error)
    }

    private fun returnSuccessOrFailed(
        result: Response,
        event: SlackEventPayload,
        commandType: CommandType = CommandType.RESPONSE,
    ) = if (result.isSuccessful) {
        CommandOutput.success(payload = event, commandType = commandType)
    } else {
        CommandOutput.fail(event = event, reason = result.message)
    }
}
