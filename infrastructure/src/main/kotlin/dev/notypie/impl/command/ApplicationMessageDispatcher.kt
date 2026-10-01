package dev.notypie.impl.command

import com.slack.api.RequestConfigurator
import com.slack.api.Slack
import com.slack.api.SlackConfig
import com.slack.api.methods.SlackApiException
import com.slack.api.methods.SlackApiTextResponse
import com.slack.api.methods.response.chat.ChatPostEphemeralResponse
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.methods.response.chat.ChatUpdateResponse
import com.slack.api.util.http.SlackHttpClient.buildOkHttpClient
import dev.notypie.common.jsonMapper
import dev.notypie.domain.command.dto.response.CommandOutput
import dev.notypie.domain.command.dto.response.Status
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.retry.RetryException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLHandshakeException

private val dispatcherLog = KotlinLogging.logger {}

private val TRANSIENT_SLACK_ERRORS = setOf("internal_error", "service_unavailable")
private val SLACK_RESPONSE_URL_HOSTS = setOf("hooks.slack.com", "hooks.slack-gov.com")
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
private const val HTTPS_PORT = 443
private const val MAX_RESPONSE_BODY_BYTES = 4_096L
private const val MAX_FAILURE_REASON_CHARS = 200
private val MAX_INLINE_RETRY_AFTER: Duration = Duration.ofSeconds(3L)
private val IDEMPOTENT_TRANSIENT_EXCEPTIONS: List<Class<out Throwable>> =
    listOf(IOException::class.java, SlackApiException::class.java, SlackTransientErrorException::class.java)

// A post that times out after it was sent may already be on Slack; only retry failures that never reached it.
private val NOT_SENT_TRANSIENT_EXCEPTIONS: List<Class<out Throwable>> =
    listOf(
        ConnectException::class.java,
        UnknownHostException::class.java,
        NoRouteToHostException::class.java,
        SSLHandshakeException::class.java,
        SlackApiException::class.java,
        SlackTransientErrorException::class.java,
    )
val SLACK_CALL_TIMEOUT: Duration = Duration.ofSeconds(6L)
const val RATE_LIMITED_REASON = "ratelimited"
const val TRANSIENT_EXHAUSTED_REASON = "transient_exhausted"
const val OUTCOME_UNKNOWN_REASON = "outcome_unknown"

fun CommandOutput.isRateLimited(): Boolean = !ok && errorReason == RATE_LIMITED_REASON

fun CommandOutput.isTransientExhausted(): Boolean = !ok && errorReason == TRANSIENT_EXHAUSTED_REASON

fun CommandOutput.retryAfter(): Duration? = (this as? RateLimitedOutput)?.retryAfter

class RateLimitedOutput(
    event: SlackEventPayload,
    val retryAfter: Duration?,
) : CommandOutput(
        ok = false,
        apiAppId = event.apiAppId,
        status = Status.FAILED,
        commandDetailType = event.commandDetailType,
        idempotencyKey = event.idempotencyKey,
        publisherId = event.publisherId,
        channel = event.channel,
        commandType = CommandType.SIMPLE,
        errorReason = RATE_LIMITED_REASON,
    )

fun slackClient(callTimeout: Duration = SLACK_CALL_TIMEOUT): Slack =
    Slack.getInstance(
        SlackConfig().apply {
            isStatsEnabled = false
            httpClientCallTimeoutMillis = callTimeout.toMillis().toInt()
        },
    )

fun responseUrlClient(slack: Slack): OkHttpClient =
    buildOkHttpClient(slack.config)
        .newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

class SlackTransientErrorException(
    val error: String,
) : RuntimeException("Slack transient error: $error")

class SlackRateLimitedException(
    val retryAfter: Duration?,
) : RuntimeException("Slack rate limited, Retry-After=${retryAfter?.toSeconds()}s")

class ApplicationMessageDispatcher(
    private val botToken: String,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val retryService: RetryService,
    private val slack: Slack = slackClient(),
    private val okHttpClient: OkHttpClient = responseUrlClient(slack = slack),
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : MessageDispatcher {
    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    override fun dispatch(event: SlackEventPayload): CommandOutput {
        if (event is OpenViewPayloadContents) {
            throw UnsupportedOperationException(
                "OpenViewPayloadContents cannot be dispatched via the outbox relay path — " +
                    "trigger_id expires in 3s. Use dispatchImmediate(event) on the request " +
                    "thread instead. idempotencyKey=${event.idempotencyKey}",
            )
        }
        val transientExceptions = transientExceptionsFor(event = event)
        return try {
            withRateLimitRetry(event = event) {
                retryService.execute(action = { dispatchOnce(event = event) }, exceptions = transientExceptions)
            }
        } catch (exception: RetryException) {
            val cause = exception.cause
            when {
                transientExceptions.any { it.isInstance(cause) } -> {
                    dispatcherLog.warn(exception) {
                        "Slack transient failure outlasted the retries for ${event.commandDetailType}; " +
                            "leaving it to the outbox recovery sweep"
                    }
                    failOutput(event = event, reason = TRANSIENT_EXHAUSTED_REASON)
                }

                cause is IOException -> {
                    dispatcherLog.warn(exception) {
                        "Slack call for ${event.commandDetailType} failed after it may have been sent; " +
                            "not resending idempotencyKey=${event.idempotencyKey}"
                    }
                    failOutput(event = event, reason = "$OUTCOME_UNKNOWN_REASON: ${cause::class.java.simpleName}")
                }

                else -> throw exception
            }
        }
    }

    private fun transientExceptionsFor(event: SlackEventPayload): List<Class<out Throwable>> =
        if (event is PostEventPayloadContents && event.messageType == MessageType.UPDATE_MESSAGE) {
            IDEMPOTENT_TRANSIENT_EXCEPTIONS
        } else {
            NOT_SENT_TRANSIENT_EXCEPTIONS
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

    private fun withRateLimitRetry(event: SlackEventPayload, block: () -> CommandOutput): CommandOutput {
        val rateLimited =
            try {
                return block()
            } catch (exception: Exception) {
                exception.asRateLimited() ?: throw exception
            }
        val wait =
            rateLimited.retryAfter?.takeIf { it <= MAX_INLINE_RETRY_AFTER }
                ?: return rateLimitedOutput(event = event, retryAfter = rateLimited.retryAfter)
        dispatcherLog.warn { "Slack rate limited ${event.commandDetailType}; waiting ${wait.toMillis()}ms once" }
        try {
            sleeper(wait)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return rateLimitedOutput(event = event, retryAfter = wait)
        }
        return try {
            block()
        } catch (exception: Exception) {
            val again = exception.asRateLimited() ?: throw exception
            rateLimitedOutput(event = event, retryAfter = again.retryAfter)
        }
    }

    private fun rateLimitedOutput(event: SlackEventPayload, retryAfter: Duration?): CommandOutput {
        dispatcherLog.warn {
            "Slack rate limited ${event.commandDetailType} (Retry-After=${retryAfter?.toSeconds()}s); " +
                "leaving it to the outbox recovery sweep"
        }
        return RateLimitedOutput(event = event, retryAfter = retryAfter)
    }

    private fun Exception.asRateLimited(): SlackRateLimitedException? =
        generateSequence<Throwable>(this) { it.cause }.filterIsInstance<SlackRateLimitedException>().firstOrNull()

    private fun dispatchEphemeralContents(event: PostEventPayloadContents) =
        dispatchPostContents(
            event = event,
            apiMethod = "chat.postEphemeral",
            responseType = ChatPostEphemeralResponse::class.java,
        )

    private fun dispatchChatPostMessageContents(event: PostEventPayloadContents) =
        dispatchPostContents(
            event = event,
            apiMethod = "chat.postMessage",
            responseType = ChatPostMessageResponse::class.java,
        )

    private fun dispatchChatUpdateContents(event: PostEventPayloadContents) =
        dispatchPostContents(event = event, apiMethod = "chat.update", responseType = ChatUpdateResponse::class.java)

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
                val code = exception.response.code
                if (code == HTTP_TOO_MANY_REQUESTS) {
                    throw SlackRateLimitedException(
                        retryAfter = parseRetryAfter(value = exception.response.header("Retry-After")),
                    )
                }
                if (code >= HTTP_SERVER_ERROR) throw exception
                return failOutput(
                    event = event,
                    reason = "http_$code: ${exception.responseBody.orEmpty().take(MAX_FAILURE_REASON_CHARS)}",
                )
            }
        return buildCommandOutputFromResponse(result = result, event = event)
    }

    private fun parseRetryAfter(value: String?): Duration? {
        val trimmed = value?.trim() ?: return null
        trimmed.toLongOrNull()?.let { return Duration.ofSeconds(it.coerceAtLeast(0L)) }
        return runCatching { ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME) }
            .map { Duration.between(Instant.now(), it.toInstant()).coerceAtLeast(Duration.ZERO) }
            .getOrNull()
    }

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

    private fun dispatchActionResponseContents(event: ActionEventPayloadContents): CommandOutput {
        val url = event.responseUrl.toHttpUrlOrNull()
        if (url == null || !url.isHttps || url.port != HTTPS_PORT || url.host !in SLACK_RESPONSE_URL_HOSTS) {
            dispatcherLog.warn {
                "Refusing response_url outside https://$SLACK_RESPONSE_URL_HOSTS: host=${url?.host} port=${url?.port}"
            }
            return failOutput(
                event = event,
                reason = "response_url_rejected: host=${url?.host} port=${url?.port} is not a Slack hooks URL",
            )
        }
        val request =
            Request
                .Builder()
                .url(url)
                .post(event.body.toRequestBody(contentType = mediaTypeJson))
                .build()
        return okHttpClient.newCall(request).execute().use { response ->
            val body = response.peekBody(MAX_RESPONSE_BODY_BYTES).string()
            val slackError = slackErrorOf(body = body)
            val retryAfterHeader = response.header("Retry-After")
            if (response.code == HTTP_TOO_MANY_REQUESTS) {
                throw SlackRateLimitedException(retryAfter = parseRetryAfter(value = retryAfterHeader))
            }
            slackError?.let { raiseIfRetryable(error = it, retryAfterHeader = retryAfterHeader) }
            when {
                response.code >= HTTP_SERVER_ERROR ->
                    throw SlackTransientErrorException(error = "http_${response.code}")
                !response.isSuccessful ->
                    failOutput(event = event, reason = "http_${response.code}: ${body.take(MAX_FAILURE_REASON_CHARS)}")
                slackError != null -> failOutput(event = event, reason = slackError.take(MAX_FAILURE_REASON_CHARS))
                else -> successOutput(payload = event, commandType = CommandType.RESPONSE)
            }
        }
    }

    private fun raiseIfRetryable(error: String, retryAfterHeader: String?) {
        if (error == RATE_LIMITED_REASON) {
            throw SlackRateLimitedException(retryAfter = parseRetryAfter(value = retryAfterHeader))
        }
        if (error in TRANSIENT_SLACK_ERRORS) throw SlackTransientErrorException(error = error)
    }

    private fun slackErrorOf(body: String): String? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return null
        val node = runCatching { jsonMapper.readTree(trimmed) }.getOrNull() ?: return null
        if (node.path("ok").asBoolean(true)) return null
        return node.path("error").asString("unknown_error")
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
        raiseIfRetryable(
            error = result.error.orEmpty(),
            retryAfterHeader = result.httpResponseHeaders?.get("retry-after")?.firstOrNull(),
        )
        dispatcherLog.warn {
            "Slack rejected ${event.commandDetailType}: error=${result.error} warning=${result.warning}"
        }
        failOutput(event = event, reason = result.error)
    }
}
