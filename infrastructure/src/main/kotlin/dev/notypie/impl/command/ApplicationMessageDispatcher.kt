package dev.notypie.impl.command

import com.slack.api.RequestConfigurator
import com.slack.api.Slack
import com.slack.api.methods.SlackApiException
import com.slack.api.methods.SlackApiTextResponse
import com.slack.api.methods.response.chat.ChatPostEphemeralResponse
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.methods.response.chat.ChatUpdateResponse
import com.slack.api.util.http.SlackHttpClient.buildOkHttpClient
import dev.notypie.common.jsonMapper
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
import java.io.IOException
import java.time.Duration

private val dispatcherLog = KotlinLogging.logger {}

// Slack answered 200 with ok=false and one of these codes: the request was fine, Slack was not. Anything else
// (invalid_auth, channel_not_found, ...) is permanent and must not be retried.
private val TRANSIENT_SLACK_ERRORS =
    setOf("internal_error", "service_unavailable", "fatal_error", "request_timeout", "ratelimited")

// One in-thread wait of at most 30s; beyond that the row is left IN_PROGRESS and the outbox recovery
// sweep retries it minutes later, which keeps a Kafka listener thread well inside max.poll.interval.ms.
private const val RATE_LIMIT_ATTEMPTS = 2
private const val HTTP_TOO_MANY_REQUESTS = 429
private val MAX_RETRY_AFTER: Duration = Duration.ofSeconds(30L)
private val DEFAULT_RETRY_AFTER: Duration = Duration.ofSeconds(1L)
const val RATE_LIMITED_REASON = "ratelimited"

fun CommandOutput.isRateLimited(): Boolean = !ok && errorReason == RATE_LIMITED_REASON

class SlackTransientErrorException(
    val error: String,
) : RuntimeException("Slack transient error: $error")

class SlackRateLimitedException(
    val retryAfter: Duration,
) : RuntimeException("Slack rate limited, retry after ${retryAfter.toSeconds()}s")

class ApplicationMessageDispatcher(
    private val botToken: String,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val retryService: RetryService,
    private val slack: Slack = Slack.getInstance(),
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : MessageDispatcher {
    private val okHttpClient = buildOkHttpClient(slack.config)
    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    override fun dispatch(event: SlackEventPayload): CommandOutput {
        if (event is OpenViewPayloadContents) {
            throw UnsupportedOperationException(
                "OpenViewPayloadContents cannot be dispatched via the outbox relay path — " +
                    "trigger_id expires in 3s. Use dispatchImmediate(event) on the request " +
                    "thread instead. idempotencyKey=${event.idempotencyKey}",
            )
        }
        return withRateLimitRetry(event = event) {
            retryService.execute(
                action = { dispatchOnce(event = event) },
                exceptions =
                    listOf(
                        IOException::class.java,
                        SlackApiException::class.java,
                        SlackTransientErrorException::class.java,
                    ),
            )
        }
    }

    private fun dispatchOnce(event: SlackEventPayload): CommandOutput =
        when (event) {
            is ActionEventPayloadContents -> dispatchActionResponseContents(event = event)

            is PostEventPayloadContents ->
                when (event.messageType) {
                    MessageType.EPHEMERAL_MESSAGE -> dispatchEphemeralContents(event = event)
                    MessageType.CHANNEL_ALERT -> dispatchChatPostMessageContents(event = event)
                    MessageType.DIRECT_MESSAGE -> dispatchChatPostMessageContents(event = event)
                    MessageType.UPDATE_MESSAGE -> dispatchChatUpdateContents(event = event)
                }

            is OpenViewPayloadContents -> error("handled by dispatch()")
        }

    // HTTP 429 carries Retry-After (typically 30s+), far beyond RetryService's 10s backoff cap, so it is
    // waited out here instead of burning the retry budget; SlackApiException with any other status is
    // left to RetryService.
    private fun withRateLimitRetry(event: SlackEventPayload, block: () -> CommandOutput): CommandOutput {
        repeat(RATE_LIMIT_ATTEMPTS - 1) { attempt ->
            val rateLimited =
                try {
                    return block()
                } catch (exception: Exception) {
                    exception.asRateLimited() ?: throw exception
                }
            dispatcherLog.warn {
                "Slack rate limited ${event.commandDetailType}; waiting ${rateLimited.retryAfter.toSeconds()}s " +
                    "(attempt ${attempt + 1}/$RATE_LIMIT_ATTEMPTS)"
            }
            sleeper(rateLimited.retryAfter)
        }
        return try {
            block()
        } catch (exception: Exception) {
            exception.asRateLimited() ?: throw exception
            failOutput(event = event, reason = RATE_LIMITED_REASON)
        }
    }

    // RetryTemplate wraps even a non-retryable exception in RetryException, so look through the cause.
    private fun Exception.asRateLimited(): SlackRateLimitedException? =
        this as? SlackRateLimitedException ?: cause as? SlackRateLimitedException

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
            try {
                slack.methods().postFormWithTokenAndParseResponse(
                    requestConfigurer,
                    apiMethod,
                    botToken,
                    responseType,
                )
            } catch (exception: SlackApiException) {
                if (exception.response.code != HTTP_TOO_MANY_REQUESTS) throw exception
                throw SlackRateLimitedException(retryAfter = retryAfterOf(response = exception.response))
            }
        return buildCommandOutputFromResponse(result = result, event = event)
    }

    private fun retryAfterOf(response: Response): Duration =
        response
            .header("Retry-After")
            ?.toLongOrNull()
            ?.let { Duration.ofSeconds(it) }
            ?.coerceIn(DEFAULT_RETRY_AFTER, MAX_RETRY_AFTER)
            ?: DEFAULT_RETRY_AFTER

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

    // response_url answers 200 with a body of "ok" on success but also 200 with {"ok":false,"error":...} for
    // an expired or over-used URL, so the body decides, not the status alone.
    private fun dispatchActionResponseContents(event: ActionEventPayloadContents): CommandOutput {
        val requestBody = event.body.toRequestBody(contentType = mediaTypeJson)
        val request =
            Request
                .Builder()
                .url(event.responseUrl)
                .post(requestBody)
                .build()
        return okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.code == HTTP_TOO_MANY_REQUESTS ->
                    throw SlackRateLimitedException(retryAfter = retryAfterOf(response = response))
                response.code >= 500 -> throw SlackTransientErrorException(error = "http_${response.code}")
                !response.isSuccessful -> failOutput(event = event, reason = "http_${response.code}: ${body.take(200)}")
                isSlackFailureBody(body = body) -> failOutput(event = event, reason = body.take(200))
                else -> successOutput(payload = event, commandType = CommandType.RESPONSE)
            }
        }
    }

    // response_url answers a bare "ok" on success and JSON {"ok":false,"error":...} otherwise.
    private fun isSlackFailureBody(body: String): Boolean {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return false
        return runCatching { jsonMapper.readTree(trimmed).path("ok").asBoolean(true) }.getOrDefault(true).not()
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
    } else if (result.error in TRANSIENT_SLACK_ERRORS) {
        throw SlackTransientErrorException(error = result.error)
    } else {
        // Surface Slack-side rejections (ok=false); otherwise the message silently never arrives.
        dispatcherLog.warn {
            "Slack rejected ${event.commandDetailType}: error=${result.error} warning=${result.warning}"
        }
        failOutput(event = event, reason = result.error)
    }
}
