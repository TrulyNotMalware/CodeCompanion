package dev.notypie.impl.calendar

import dev.notypie.common.jsonMapper
import dev.notypie.impl.cve.SourceResponse
import dev.notypie.impl.cve.sendWithinDeadline
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.core.JacksonException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

private val log = KotlinLogging.logger {}

data class CalendarEventBody(
    val summary: String,
    val description: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val timeZone: ZoneId,
    val meetingUid: UUID,
)

sealed interface CalendarApiResult {
    data class Ok(
        val eventId: String,
    ) : CalendarApiResult

    data object Gone : CalendarApiResult

    data object AlreadyExists : CalendarApiResult

    data object Unauthorized : CalendarApiResult

    data class RateLimited(
        val retryAfter: Duration?,
    ) : CalendarApiResult

    data class Misconfigured(
        val message: String,
    ) : CalendarApiResult

    data class Failed(
        val statusCode: Int?,
        val message: String,
    ) : CalendarApiResult
}

class GoogleCalendarClient(
    private val requestTimeout: Duration,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://www.googleapis.com/calendar/v3"
        const val MEETING_UID_PROPERTY = "codecompanionMeetingUid"
        private const val CONFIRMED_STATUS = "confirmed"
        private const val DEFAULT_MAX_BODY_BYTES = 64 * 1024
        private const val JSON_CONTENT_TYPE = "application/json"
        private val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded")
        private val API_DISABLED_REASONS = setOf("accessNotConfigured", "SERVICE_DISABLED")
        private val MAX_RETRY_AFTER: Duration = Duration.ofHours(24L)
    }

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5L))
            .build()

    fun insert(accessToken: String, eventId: String, event: CalendarEventBody): CalendarApiResult {
        val request =
            authorized(uri = eventsUri(), accessToken = accessToken)
                .header("Content-Type", JSON_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(eventJson(event = event, eventId = eventId)))
                .build()
        return execute(request = request, operation = "insert") { response -> eventIdOf(response = response) }
    }

    fun patch(accessToken: String, eventId: String, event: CalendarEventBody): CalendarApiResult {
        val request =
            authorized(uri = eventUri(eventId = eventId), accessToken = accessToken)
                .header("Content-Type", JSON_CONTENT_TYPE)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(eventJson(event = event, eventId = null)))
                .build()
        return execute(request = request, operation = "patch") { response -> eventIdOf(response = response) }
    }

    fun delete(accessToken: String, eventId: String): CalendarApiResult {
        val request =
            authorized(uri = eventUri(eventId = eventId), accessToken = accessToken)
                .DELETE()
                .build()
        return execute(request = request, operation = "delete") { CalendarApiResult.Ok(eventId = eventId) }
    }

    private fun eventsUri(): URI = URI.create("$baseUrl/calendars/primary/events")

    private fun eventUri(eventId: String): URI =
        URI.create(
            "$baseUrl/calendars/primary/events/" +
                URLEncoder.encode(eventId, StandardCharsets.UTF_8).replace(oldValue = "+", newValue = "%20"),
        )

    private fun authorized(uri: URI, accessToken: String): HttpRequest.Builder =
        HttpRequest
            .newBuilder(uri)
            .timeout(requestTimeout)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", JSON_CONTENT_TYPE)

    private fun eventJson(event: CalendarEventBody, eventId: String?): String =
        jsonMapper.writeValueAsString(
            listOfNotNull(
                eventId?.let { "id" to it },
                "status" to CONFIRMED_STATUS,
                "summary" to event.summary,
                "description" to event.description,
                "start" to dateTimeOf(dateTime = event.start, timeZone = event.timeZone),
                "end" to dateTimeOf(dateTime = event.end, timeZone = event.timeZone),
                "extendedProperties" to
                    mapOf("private" to mapOf(MEETING_UID_PROPERTY to event.meetingUid.toString())),
            ).toMap(),
        )

    private fun dateTimeOf(dateTime: LocalDateTime, timeZone: ZoneId): Map<String, String> =
        mapOf(
            "dateTime" to dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            "timeZone" to timeZone.id,
        )

    private fun execute(
        request: HttpRequest,
        operation: String,
        onSuccess: (SourceResponse) -> CalendarApiResult,
    ): CalendarApiResult {
        val response =
            try {
                httpClient.sendWithinDeadline(request = request, deadline = requestTimeout, maxBodyBytes = maxBodyBytes)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw exception
            } catch (exception: Exception) {
                log.warn(exception) { "Google Calendar $operation request failed" }
                return CalendarApiResult.Failed(
                    statusCode = null,
                    message = "request failed: ${exception.javaClass.simpleName}",
                )
            }
        val status = response.statusCode
        if (status in 200..299) return onSuccess(response)
        if (status == 404 || status == 410) {
            log.info { "Google Calendar $operation returned $status: the event no longer exists" }
            return CalendarApiResult.Gone
        }
        if (status == 409) {
            log.info { "Google Calendar $operation returned 409: an event with this id already exists" }
            return CalendarApiResult.AlreadyExists
        }
        val error = apiErrorOf(body = response.body)
        log.warn { "Google Calendar $operation failed: status=$status message=${error.message}" }
        val apiDisabledReason = error.reasons.firstOrNull { it in API_DISABLED_REASONS }
        return when {
            status == 401 -> CalendarApiResult.Unauthorized
            status == 429 || (status == 403 && error.reasons.any { it in RATE_LIMIT_REASONS }) ->
                CalendarApiResult.RateLimited(retryAfter = retryAfterOf(response = response))
            status == 403 && apiDisabledReason != null ->
                CalendarApiResult.Misconfigured(
                    message =
                        "Google Calendar API is not enabled for the OAuth client's project " +
                            "($apiDisabledReason): ${error.message}",
                )
            else -> CalendarApiResult.Failed(statusCode = status, message = error.message.ifBlank { "HTTP $status" })
        }
    }

    private fun eventIdOf(response: SourceResponse): CalendarApiResult {
        val eventId =
            try {
                jsonMapper.readTree(response.body).path("id").asString("")
            } catch (exception: JacksonException) {
                log.warn { "Google Calendar returned a ${response.statusCode} body that is not JSON" }
                return CalendarApiResult.Failed(statusCode = response.statusCode, message = "response is not JSON")
            }
        if (eventId.isBlank()) {
            log.warn { "Google Calendar returned ${response.statusCode} without an event id" }
            return CalendarApiResult.Failed(statusCode = response.statusCode, message = "response has no event id")
        }
        return CalendarApiResult.Ok(eventId = eventId)
    }

    private fun retryAfterOf(response: SourceResponse): Duration? =
        response.headers
            .firstValue("Retry-After")
            .getOrNull()
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
            ?.let { minOf(Duration.ofSeconds(it), MAX_RETRY_AFTER) }

    private fun apiErrorOf(body: String): GoogleApiError =
        try {
            val error = jsonMapper.readTree(body).path("error")
            GoogleApiError(
                message = error.path("message").asString(""),
                reasons =
                    listOf("errors", "details")
                        .flatMap { field -> error.path(field).values() }
                        .map { it.path("reason").asString("") }
                        .filter { it.isNotBlank() }
                        .toSet(),
            )
        } catch (exception: JacksonException) {
            log.debug(exception) { "Google Calendar error body is not JSON" }
            GoogleApiError(message = "", reasons = emptySet())
        }

    private data class GoogleApiError(
        val message: String,
        val reasons: Set<String>,
    )
}
