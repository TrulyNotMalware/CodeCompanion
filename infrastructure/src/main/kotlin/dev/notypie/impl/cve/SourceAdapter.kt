package dev.notypie.impl.cve

import dev.notypie.repository.cve.CveTopic
import dev.notypie.repository.cve.schema.CveSourceType
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class RawSourceEvent(
    val externalId: String,
    val title: String,
    val rawContent: String,
    val publishedAt: LocalDateTime?,
)

interface SourceAdapter {
    fun supports(sourceType: CveSourceType): Boolean

    fun fetch(topic: CveTopic): List<RawSourceEvent>
}

internal data class SourceResponse(
    val statusCode: Int,
    val headers: HttpHeaders,
    val body: String,
)

internal class SourceBodyTooLargeException(
    maxBodyBytes: Int,
) : IOException("response body exceeded $maxBodyBytes bytes")

internal class SourceBodyTimeoutException(
    deadline: Duration,
    cause: Throwable,
) : IOException("response body not received within ${deadline.toMillis()} ms", cause)

private val sourceBodyWatchdog: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "cve-source-body-watchdog").apply { isDaemon = true }
    }

internal fun HttpClient.sendWithinDeadline(
    request: HttpRequest,
    deadline: Duration,
    maxBodyBytes: Int,
): SourceResponse {
    val startedAt = System.nanoTime()
    val response = send(request, HttpResponse.BodyHandlers.ofInputStream())
    val stream = response.body()
    val timedOut = AtomicBoolean(false)
    val watchdog =
        sourceBodyWatchdog.schedule(
            {
                timedOut.set(true)
                runCatching { stream.close() }
            },
            deadline.minusNanos(System.nanoTime() - startedAt).toNanos().coerceAtLeast(0L),
            TimeUnit.NANOSECONDS,
        )
    try {
        val bytes = stream.use { it.readNBytes(maxBodyBytes + 1) }
        if (bytes.size > maxBodyBytes) throw SourceBodyTooLargeException(maxBodyBytes = maxBodyBytes)
        return SourceResponse(
            statusCode = response.statusCode(),
            headers = response.headers(),
            body = bytes.decodeToString(),
        )
    } catch (exception: IOException) {
        if (timedOut.get()) throw SourceBodyTimeoutException(deadline = deadline, cause = exception)
        throw exception
    } finally {
        watchdog.cancel(false)
    }
}

internal fun JsonNode.stringOrNull(): String? =
    if (isValueNode && !isNull && !isMissingNode) asString().takeIf { it.isNotBlank() } else null

internal fun parseSourceTimestamp(value: String?): LocalDateTime? {
    if (value == null) return null
    return runCatching { OffsetDateTime.parse(value).toLocalDateTime() }
        .recoverCatching { LocalDateTime.parse(value) }
        .getOrNull()
}
