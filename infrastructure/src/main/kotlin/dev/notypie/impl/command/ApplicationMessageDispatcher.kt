package dev.notypie.impl.command

import com.google.gson.JsonParseException
import com.slack.api.RequestConfigurator
import com.slack.api.Slack
import com.slack.api.SlackConfig
import com.slack.api.methods.SlackApiException
import com.slack.api.methods.SlackApiTextResponse
import com.slack.api.methods.response.chat.ChatPostEphemeralResponse
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.methods.response.chat.ChatUpdateResponse
import com.slack.api.util.http.SlackHttpClient
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
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.retry.RetryException
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val dispatcherLog = KotlinLogging.logger {}

// internal_error "may have partly succeeded" per Slack, so only an idempotent call retries it.
private val TRANSIENT_SLACK_ERRORS = setOf("internal_error", "service_unavailable")
private val NOT_SENT_SLACK_ERRORS = setOf("service_unavailable")
private val SLACK_RESPONSE_URL_HOSTS = setOf("hooks.slack.com", "hooks.slack-gov.com")
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
private const val HTTP_SERVICE_UNAVAILABLE = 503
private const val HTTPS_PORT = 443
private const val CHAT_POST_MESSAGE_METHOD = "chat.postMessage"
private const val CHAT_POST_EPHEMERAL_METHOD = "chat.postEphemeral"
private const val CHAT_UPDATE_METHOD = "chat.update"
private const val VIEWS_OPEN_METHOD = "views.open"
private const val RESPONSE_URL_METHOD = "response_url"
private const val MAX_RESPONSE_BODY_BYTES = 4_096L
private const val MAX_FAILURE_REASON_CHARS = 200
private val MAX_INLINE_RETRY_AFTER: Duration = Duration.ofSeconds(3L)
private val IDEMPOTENT_TRANSIENT_EXCEPTIONS: List<Class<out Throwable>> =
    listOf(IOException::class.java, SlackApiException::class.java, SlackTransientErrorException::class.java)

// A post that failed after its request was written may already be on Slack; only retry failures before that.
private val NOT_SENT_TRANSIENT_EXCEPTIONS: List<Class<out Throwable>> =
    listOf(SlackRequestNotSentException::class.java, SlackTransientErrorException::class.java)
val SLACK_CALL_TIMEOUT: Duration = Duration.ofSeconds(6L)
const val RATE_LIMITED_REASON = "ratelimited"
const val TRANSIENT_EXHAUSTED_REASON = "transient_exhausted"
const val OUTCOME_UNKNOWN_REASON = "outcome_unknown"
const val UNSPECIFIED_ERROR_REASON = "unspecified_error"
const val ACCESS_BLOCKED_REASON = "access_blocked"

fun CommandOutput.isRateLimited(): Boolean = !ok && errorReason == RATE_LIMITED_REASON

fun CommandOutput.isTransientExhausted(): Boolean = !ok && errorReason == TRANSIENT_EXHAUSTED_REASON

fun CommandOutput.isAccessBlocked(): Boolean = !ok && errorReason == ACCESS_BLOCKED_REASON

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

private enum class RequestProgress { UNTRACKED, NOT_WRITTEN, HEADERS_WRITTEN }

// Synchronous OkHttp calls emit these events on the calling thread, so a thread-local scopes them to one attempt.
object RequestProgressListener : EventListener() {
    private val progress = ThreadLocal.withInitial { RequestProgress.UNTRACKED }

    fun reset() = progress.set(RequestProgress.UNTRACKED)

    fun requestNeverWritten(): Boolean = progress.get() == RequestProgress.NOT_WRITTEN

    fun requestHeadersWritten(): Boolean = progress.get() == RequestProgress.HEADERS_WRITTEN

    override fun callStart(call: Call) = progress.set(RequestProgress.NOT_WRITTEN)

    // End, not start: on HTTP/2 the header write opens the stream, which fails unsent on a connection already shut down.
    override fun requestHeadersEnd(call: Call, request: Request) = progress.set(RequestProgress.HEADERS_WRITTEN)
}

// OkHttp's own retry would re-send a written POST after a reset, behind the dispatcher's back.
fun slackOkHttpClient(config: SlackConfig): OkHttpClient =
    buildOkHttpClient(config)
        .newBuilder()
        .retryOnConnectionFailure(false)
        .eventListenerFactory { RequestProgressListener }
        .build()

fun slackConfig(callTimeout: Duration = SLACK_CALL_TIMEOUT): SlackConfig =
    SlackConfig().apply {
        isStatsEnabled = false
        httpClientCallTimeoutMillis = callTimeout.toMillis().toInt()
    }

fun slackClient(config: SlackConfig = slackConfig()): Slack =
    Slack.getInstance(config, SlackHttpClient(slackOkHttpClient(config = config)))

fun responseUrlClient(slack: Slack): OkHttpClient =
    slackOkHttpClient(config = slack.config)
        .newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

class SlackRequestNotSentException(
    cause: IOException,
) : RuntimeException("Slack request failed before Slack processed it: ${cause::class.java.simpleName}", cause)

class SlackResponseUnreadableException(
    cause: RuntimeException,
) : IOException("Slack answered with a body that could not be read: ${cause::class.java.simpleName}", cause)

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
    private val onOutcomeUnknown: (slackMethod: String) -> Unit,
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
        val idempotent = isIdempotent(event = event)
        val transientExceptions = if (idempotent) IDEMPOTENT_TRANSIENT_EXCEPTIONS else NOT_SENT_TRANSIENT_EXCEPTIONS
        return try {
            withRateLimitRetry(event = event) {
                retryService.execute(
                    action = { attempt(event = event, idempotent = idempotent) },
                    exceptions = transientExceptions,
                )
            }
        } catch (exception: RetryException) {
            val cause = exception.cause
            val chain = generateSequence(cause) { it.cause }.toList()
            when {
                chain.any { link -> transientExceptions.any { it.isInstance(link) } } -> {
                    dispatcherLog.warn(exception) {
                        "Slack transient failure outlasted the retries for ${event.commandDetailType}; " +
                            "leaving it to the outbox recovery sweep"
                    }
                    failOutput(event = event, reason = TRANSIENT_EXHAUSTED_REASON)
                }

                cause is IOException ->
                    outcomeUnknown(event = event, what = cause::class.java.simpleName, cause = exception)

                else -> throw exception
            }
        }
    }

    private fun isIdempotent(event: SlackEventPayload): Boolean =
        event is PostEventPayloadContents && event.messageType == MessageType.UPDATE_MESSAGE

    private fun attempt(event: SlackEventPayload, idempotent: Boolean): CommandOutput {
        RequestProgressListener.reset()
        return try {
            dispatchOnce(event = event, idempotent = idempotent)
        } catch (exception: IOException) {
            if (RequestProgressListener.requestNeverWritten() || exception.isRefusedStream()) {
                throw SlackRequestNotSentException(cause = exception)
            }
            throw exception
        }
    }

    private fun IOException.isRefusedStream(): Boolean =
        this is StreamResetException && errorCode == ErrorCode.REFUSED_STREAM

    private fun dispatchOnce(event: SlackEventPayload, idempotent: Boolean): CommandOutput =
        when (event) {
            is ActionEventPayloadContents -> dispatchActionResponseContents(event = event, idempotent = idempotent)

            is PostEventPayloadContents ->
                when (event.messageType) {
                    MessageType.EPHEMERAL_MESSAGE -> dispatchEphemeralContents(event = event, idempotent = idempotent)
                    MessageType.CHANNEL_ALERT -> dispatchChatPostMessageContents(event = event, idempotent = idempotent)
                    MessageType.DIRECT_MESSAGE ->
                        dispatchChatPostMessageContents(
                            event = event,
                            idempotent = idempotent,
                        )
                    MessageType.UPDATE_MESSAGE -> dispatchChatUpdateContents(event = event, idempotent = idempotent)
                }

            is OpenViewPayloadContents -> error("handled by dispatch()")
        }

    private fun serverErrorOutcome(event: SlackEventPayload, code: Int, idempotent: Boolean): CommandOutput {
        if (idempotent || code == HTTP_SERVICE_UNAVAILABLE) throw SlackTransientErrorException(error = "http_$code")
        return outcomeUnknown(event = event, what = "http_$code")
    }

    private fun outcomeUnknown(event: SlackEventPayload, what: String, cause: Throwable? = null): CommandOutput {
        val slackMethod = slackMethodOf(event = event)
        dispatcherLog.warn(cause) {
            "$slackMethod for ${event.commandDetailType} ended with $what and may already have been delivered; " +
                "not resending idempotencyKey=${event.idempotencyKey}"
        }
        onOutcomeUnknown(slackMethod)
        return failOutput(event = event, reason = "$OUTCOME_UNKNOWN_REASON: $what")
    }

    private fun slackMethodOf(event: SlackEventPayload): String =
        when (event) {
            is ActionEventPayloadContents -> RESPONSE_URL_METHOD
            is OpenViewPayloadContents -> VIEWS_OPEN_METHOD
            is PostEventPayloadContents ->
                when (event.messageType) {
                    MessageType.EPHEMERAL_MESSAGE -> CHAT_POST_EPHEMERAL_METHOD
                    MessageType.CHANNEL_ALERT, MessageType.DIRECT_MESSAGE -> CHAT_POST_MESSAGE_METHOD
                    MessageType.UPDATE_MESSAGE -> CHAT_UPDATE_METHOD
                }
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

    private fun dispatchEphemeralContents(event: PostEventPayloadContents, idempotent: Boolean) =
        dispatchPostContents(
            event = event,
            apiMethod = CHAT_POST_EPHEMERAL_METHOD,
            responseType = ChatPostEphemeralResponse::class.java,
            idempotent = idempotent,
        )

    private fun dispatchChatPostMessageContents(event: PostEventPayloadContents, idempotent: Boolean) =
        dispatchPostContents(
            event = event,
            apiMethod = CHAT_POST_MESSAGE_METHOD,
            responseType = ChatPostMessageResponse::class.java,
            idempotent = idempotent,
        )

    private fun dispatchChatUpdateContents(event: PostEventPayloadContents, idempotent: Boolean) =
        dispatchPostContents(
            event = event,
            apiMethod = CHAT_UPDATE_METHOD,
            responseType = ChatUpdateResponse::class.java,
            idempotent = idempotent,
        )

    private fun <T : SlackApiTextResponse> dispatchPostContents(
        event: PostEventPayloadContents,
        apiMethod: String,
        responseType: Class<T>,
        idempotent: Boolean,
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
            } catch (exception: RuntimeException) {
                // A 2xx body goes through Gson in the SDK: bad JSON throws, an empty body ends in an NPE. Slack answered.
                val unreadable = exception is JsonParseException || exception is NullPointerException
                if (unreadable && RequestProgressListener.requestHeadersWritten()) {
                    throw SlackResponseUnreadableException(cause = exception)
                }
                throw exception
            } catch (exception: SlackApiException) {
                val code = exception.response.code
                if (code == HTTP_TOO_MANY_REQUESTS) {
                    throw SlackRateLimitedException(
                        retryAfter = parseRetryAfter(value = exception.response.header("Retry-After")),
                    )
                }
                if (code >=
                    HTTP_SERVER_ERROR
                ) {
                    return serverErrorOutcome(event = event, code = code, idempotent = idempotent)
                }
                return failOutput(
                    event = event,
                    reason = "http_$code: ${exception.responseBody.orEmpty().take(MAX_FAILURE_REASON_CHARS)}",
                )
            }
        return buildCommandOutputFromResponse(result = result, event = event, idempotent = idempotent)
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

    private fun dispatchActionResponseContents(event: ActionEventPayloadContents, idempotent: Boolean): CommandOutput {
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
            slackError?.let {
                raiseIfRetryable(
                    error = it,
                    retryAfterHeader = retryAfterHeader,
                    idempotent = idempotent,
                )
            }
            when {
                response.code >= HTTP_SERVER_ERROR ->
                    serverErrorOutcome(event = event, code = response.code, idempotent = idempotent)
                slackError != null && slackError in TRANSIENT_SLACK_ERRORS ->
                    outcomeUnknown(event = event, what = slackError)
                !response.isSuccessful ->
                    failOutput(event = event, reason = "http_${response.code}: ${body.take(MAX_FAILURE_REASON_CHARS)}")
                slackError != null -> failOutput(event = event, reason = slackError.take(MAX_FAILURE_REASON_CHARS))
                else -> successOutput(payload = event, commandType = CommandType.RESPONSE)
            }
        }
    }

    private fun raiseIfRetryable(error: String, retryAfterHeader: String?, idempotent: Boolean) {
        if (error == RATE_LIMITED_REASON) {
            throw SlackRateLimitedException(retryAfter = parseRetryAfter(value = retryAfterHeader))
        }
        val retryable = if (idempotent) TRANSIENT_SLACK_ERRORS else NOT_SENT_SLACK_ERRORS
        if (error in retryable) throw SlackTransientErrorException(error = error)
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
        idempotent: Boolean,
        commandType: CommandType = CommandType.EXTERNAL_API,
    ): CommandOutput {
        if (result.isOk) {
            return successOutput(
                payload = event,
                commandType = commandType,
                messageTs = (result as? ChatPostMessageResponse)?.ts.orEmpty(),
            )
        }
        val error = result.error.orEmpty()
        raiseIfRetryable(
            error = error,
            retryAfterHeader = result.httpResponseHeaders?.get("retry-after")?.firstOrNull(),
            idempotent = idempotent,
        )
        if (error in TRANSIENT_SLACK_ERRORS) return outcomeUnknown(event = event, what = error)
        dispatcherLog.warn {
            "Slack rejected ${event.commandDetailType}: error=${result.error} warning=${result.warning}"
        }
        return failOutput(event = event, reason = error.ifEmpty { UNSPECIFIED_ERROR_REASON })
    }
}
