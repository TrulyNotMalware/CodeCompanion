package dev.notypie.impl.command

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
import okhttp3.ConnectionPool
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.retry.RetryException
import java.io.IOException
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private val dispatcherLog = KotlinLogging.logger {}

// Slack answers service_unavailable before acting; internal_error "may have partly succeeded" (Slack docs), so it is
// retried only for an idempotent call.
private const val SLACK_UNAVAILABLE_ERROR = "service_unavailable"
private const val SLACK_INTERNAL_ERROR = "internal_error"

// Token- or workspace-wide refusals from the chat.postMessage error list on docs.slack.dev: every row fails the same
// way until the token, its scopes or the workspace are fixed, so the row is held instead of failed.
private val SLACK_ACCESS_ERRORS =
    setOf(
        "invalid_auth",
        "not_authed",
        "account_inactive",
        "token_revoked",
        "token_expired",
        "missing_scope",
        "no_permission",
        "not_allowed_token_type",
        "team_access_not_granted",
        "accesslimited",
        "ekm_access_denied",
        "org_login_required",
        "team_added_to_org",
    )
private val SLACK_RESPONSE_URL_HOSTS = setOf("hooks.slack.com", "hooks.slack-gov.com")
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
private const val HTTP_SERVICE_UNAVAILABLE = 503
private const val HTTPS_PORT = 443
private const val MAX_RESPONSE_BODY_BYTES = 4_096L
private const val MAX_FAILURE_REASON_CHARS = 200
private const val RESPONSE_URL_CALL = "response_url POST"
private val MAX_INLINE_RETRY_AFTER: Duration = Duration.ofSeconds(3L)

// No outbox row outlives outbox.polling.give-up-after-hours (24 h) anyway; the bound keeps an absurd Retry-After from
// overflowing the relay's LocalDateTime arithmetic.
internal val MAX_RETRY_AFTER: Duration = Duration.ofHours(24L)
private val TRANSIENT_EXCEPTIONS: List<Class<out Throwable>> =
    listOf(IOException::class.java, SlackApiException::class.java, SlackTransientErrorException::class.java)
val SLACK_CALL_TIMEOUT: Duration = Duration.ofSeconds(6L)
const val RATE_LIMITED_REASON = "ratelimited"
const val TRANSIENT_EXHAUSTED_REASON = "transient_exhausted"
const val OUTCOME_UNKNOWN_REASON = "outcome_unknown"
const val ACCESS_BLOCKED_REASON = "access_blocked"

// How long the relay holds an access-blocked row before trying it again: long enough not to hammer Slack with a
// dead token, short enough that rows go out soon after the token or workspace is fixed.
val ACCESS_BLOCKED_DEFER: Duration = Duration.ofMinutes(15L)

fun CommandOutput.isRateLimited(): Boolean = !ok && errorReason == RATE_LIMITED_REASON

fun CommandOutput.isTransientExhausted(): Boolean = !ok && errorReason == TRANSIENT_EXHAUSTED_REASON

fun CommandOutput.isOutcomeUnknown(): Boolean = !ok && errorReason == OUTCOME_UNKNOWN_REASON

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

// Pooled connections idle longer than this are closed rather than reused. OkHttp runs an extensive health check before
// it reuses a pooled connection for a non-GET, which catches a connection the server has already closed; what is left
// is a server closing it while our request is in flight, most likely near the server's own idle timeout. Without
// OkHttp's transparent retry that call ends outcome unknown, so connections are not kept idle long enough to get there.
private val SLACK_CONNECTION_KEEP_ALIVE: Duration = Duration.ofSeconds(20L)
private const val SLACK_MAX_IDLE_CONNECTIONS = 5

// OkHttp's retryOnConnectionFailure (on by default) resends a call on a new connection after an IOException, even once
// the whole body was written: a POST whose connection broke after Slack had read it was posted twice and reported as
// one success (review R1/F5, reproduced against a local server). Off on both clients, so every resend decision is the
// dispatcher's own (RequestSendTracker: only when the body never left). A connect failure, which OkHttp would also
// have retried on Slack's next address, now reaches RetryService instead, which retries it the same way.
private fun OkHttpClient.Builder.sendOnceAtMost(): OkHttpClient.Builder =
    retryOnConnectionFailure(false)
        .connectionPool(
            ConnectionPool(SLACK_MAX_IDLE_CONNECTIONS, SLACK_CONNECTION_KEEP_ALIVE.seconds, TimeUnit.SECONDS),
        )

// The SDK's own OkHttp client is rebuilt with RequestSendTracker so a failed non-idempotent call can tell whether
// Slack may already have acted on it.
fun slackClient(callTimeout: Duration = SLACK_CALL_TIMEOUT, configure: SlackConfig.() -> Unit = {}): Slack {
    val config =
        SlackConfig().apply {
            isStatsEnabled = false
            httpClientCallTimeoutMillis = callTimeout.toMillis().toInt()
            configure()
        }
    val okHttpClient =
        buildOkHttpClient(config)
            .newBuilder()
            .eventListenerFactory(RequestSendTracker)
            .sendOnceAtMost()
            .build()
    return Slack.getInstance(config, SlackHttpClient(okHttpClient))
}

fun responseUrlClient(slack: Slack): OkHttpClient =
    buildOkHttpClient(slack.config)
        .newBuilder()
        .eventListenerFactory(RequestSendTracker)
        .sendOnceAtMost()
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
    private val onAccessBlocked: (slackError: String) -> Unit = {},
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
        return try {
            withRateLimitRetry(event = event) {
                retryService.execute(action = { dispatchOnce(event = event) }, exceptions = TRANSIENT_EXCEPTIONS)
            }
        } catch (exception: RetryException) {
            if (TRANSIENT_EXCEPTIONS.none { it.isInstance(exception.cause) }) throw exception
            dispatcherLog.warn(exception) {
                "Slack transient failure outlasted the retries for ${event.commandDetailType}; " +
                    "leaving it to the outbox recovery sweep"
            }
            failOutput(event = event, reason = TRANSIENT_EXHAUSTED_REASON)
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
            idempotent = false,
        )

    private fun dispatchChatPostMessageContents(event: PostEventPayloadContents) =
        dispatchPostContents(
            event = event,
            apiMethod = "chat.postMessage",
            responseType = ChatPostMessageResponse::class.java,
            idempotent = false,
        )

    private fun dispatchChatUpdateContents(event: PostEventPayloadContents) =
        dispatchPostContents(
            event = event,
            apiMethod = "chat.update",
            responseType = ChatUpdateResponse::class.java,
            idempotent = true,
        )

    // A non-idempotent call is retried only when Slack cannot have acted on it: the body never left, or Slack
    // answered 503. Anything after the body was sent ends as OUTCOME_UNKNOWN_REASON instead of a second post.
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
        val probe = RequestSendProbe()
        val result =
            try {
                RequestSendTracker.track(probe = probe) {
                    slack.methods().postFormWithTokenAndParseResponse(
                        requestConfigurer,
                        apiMethod,
                        botToken,
                        responseType,
                    )
                }
            } catch (exception: SlackApiException) {
                val code = exception.response.code
                if (code == HTTP_TOO_MANY_REQUESTS) {
                    throw SlackRateLimitedException(
                        retryAfter = parseRetryAfter(value = exception.response.header("Retry-After")),
                    )
                }
                if (code >= HTTP_SERVER_ERROR) {
                    if (idempotent || code == HTTP_SERVICE_UNAVAILABLE) throw exception
                    return outcomeUnknownOutput(
                        event = event,
                        call = apiMethod,
                        detail = "http_$code",
                        cause = exception,
                    )
                }
                return failOutput(
                    event = event,
                    reason = "http_$code: ${exception.responseBody.orEmpty().take(MAX_FAILURE_REASON_CHARS)}",
                )
            } catch (exception: IOException) {
                if (idempotent || !probe.mayHaveBeenSent) throw exception
                return outcomeUnknownOutput(event = event, call = apiMethod, detail = "$exception", cause = exception)
            }
        return buildCommandOutputFromResponse(
            result = result,
            event = event,
            apiMethod = apiMethod,
            idempotent = idempotent,
        )
    }

    private fun outcomeUnknownOutput(
        event: SlackEventPayload,
        call: String,
        detail: String,
        cause: Throwable? = null,
    ): CommandOutput {
        dispatcherLog.error(cause) {
            "$call for ${event.commandDetailType} idempotencyKey=${event.idempotencyKey} may already have been " +
                "acted on by Slack ($detail); not resending it, so it is posted at most once"
        }
        return failOutput(event = event, reason = OUTCOME_UNKNOWN_REASON)
    }

    private fun parseRetryAfter(value: String?): Duration? {
        val trimmed = value?.trim() ?: return null
        trimmed.toBigIntegerOrNull()?.let { seconds ->
            val bounded = seconds.coerceIn(BigInteger.ZERO, MAX_RETRY_AFTER.seconds.toBigInteger())
            return Duration.ofSeconds(bounded.toLong())
        }
        return runCatching { ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME) }
            .map { Duration.between(Instant.now(), it.toInstant()).coerceIn(Duration.ZERO, MAX_RETRY_AFTER) }
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
        // A response_url POST can post a new message, so it follows the non-idempotent rule of dispatchPostContents.
        val probe = RequestSendProbe()
        return try {
            RequestSendTracker.track(probe = probe) { okHttpClient.newCall(request).execute() }.use { response ->
                val body = response.peekBody(MAX_RESPONSE_BODY_BYTES).string()
                val slackError = slackErrorOf(body = body)
                val retryAfterHeader = response.header("Retry-After")
                if (response.code == HTTP_TOO_MANY_REQUESTS) {
                    throw SlackRateLimitedException(retryAfter = parseRetryAfter(value = retryAfterHeader))
                }
                slackError?.let {
                    raiseIfRetryable(error = it, retryAfterHeader = retryAfterHeader, idempotent = false)
                }
                when {
                    slackError == SLACK_INTERNAL_ERROR ->
                        outcomeUnknownOutput(event = event, call = RESPONSE_URL_CALL, detail = slackError)
                    response.code == HTTP_SERVICE_UNAVAILABLE ->
                        throw SlackTransientErrorException(error = "http_${response.code}")
                    response.code >= HTTP_SERVER_ERROR ->
                        outcomeUnknownOutput(event = event, call = RESPONSE_URL_CALL, detail = "http_${response.code}")
                    !response.isSuccessful ->
                        failOutput(
                            event = event,
                            reason = "http_${response.code}: ${body.take(MAX_FAILURE_REASON_CHARS)}",
                        )
                    slackError != null -> failOutput(event = event, reason = slackError.take(MAX_FAILURE_REASON_CHARS))
                    !isAcknowledgement(body = body) ->
                        failOutput(
                            event = event,
                            reason = "unexpected_body: http_${response.code}: ${body.take(MAX_FAILURE_REASON_CHARS)}",
                        )
                    else -> successOutput(payload = event, commandType = CommandType.RESPONSE)
                }
            }
        } catch (exception: IOException) {
            if (!probe.mayHaveBeenSent) throw exception
            outcomeUnknownOutput(event = event, call = RESPONSE_URL_CALL, detail = "$exception", cause = exception)
        }
    }

    private fun raiseIfRetryable(error: String, retryAfterHeader: String?, idempotent: Boolean) {
        if (error == RATE_LIMITED_REASON) {
            throw SlackRateLimitedException(retryAfter = parseRetryAfter(value = retryAfterHeader))
        }
        if (error == SLACK_UNAVAILABLE_ERROR || (idempotent && error == SLACK_INTERNAL_ERROR)) {
            throw SlackTransientErrorException(error = error)
        }
    }

    // Slack documents a hooks.slack.com success as HTTP 200 with plain-text "ok"; JSON ok=true is accepted too.
    private fun isAcknowledgement(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed == "ok") return true
        if (!trimmed.startsWith("{")) return false
        return runCatching { jsonMapper.readTree(trimmed).path("ok").asBoolean(false) }.getOrDefault(false)
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
        apiMethod: String,
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
        if (error == SLACK_INTERNAL_ERROR) return outcomeUnknownOutput(event = event, call = apiMethod, detail = error)
        if (error in SLACK_ACCESS_ERRORS) {
            dispatcherLog.error {
                "Slack refused the bot token or workspace on $apiMethod for ${event.commandDetailType}: " +
                    "error=$error; holding idempotencyKey=${event.idempotencyKey} until the configuration is fixed"
            }
            onAccessBlocked(error)
            return failOutput(event = event, reason = ACCESS_BLOCKED_REASON)
        }
        dispatcherLog.warn {
            "Slack rejected ${event.commandDetailType}: error=${result.error} warning=${result.warning}"
        }
        return failOutput(event = event, reason = result.error)
    }
}
