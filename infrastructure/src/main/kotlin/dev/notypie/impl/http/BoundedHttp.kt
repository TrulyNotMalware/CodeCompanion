package dev.notypie.impl.http

import java.io.IOException
import java.math.BigInteger
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal val MAX_RETRY_AFTER: Duration = Duration.ofHours(24L)

internal data class BoundedResponse(
    val statusCode: Int,
    val headers: HttpHeaders,
    val body: String,
)

internal class BodyTooLargeException(
    maxBodyBytes: Int,
) : IOException("response body exceeded $maxBodyBytes bytes")

internal class BodyTimeoutException(
    deadline: Duration,
    cause: Throwable,
) : IOException("response body not received within ${deadline.toMillis()} ms", cause)

private val bodyWatchdog: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "http-body-watchdog").apply { isDaemon = true }
    }

@Throws(IOException::class, InterruptedException::class)
internal fun HttpClient.sendWithinDeadline(
    request: HttpRequest,
    deadline: Duration,
    maxBodyBytes: Int,
): BoundedResponse {
    val startedAt = System.nanoTime()
    val response = send(request, HttpResponse.BodyHandlers.ofInputStream())
    val stream = response.body()
    val timedOut = AtomicBoolean(false)
    val watchdog =
        bodyWatchdog.schedule(
            {
                timedOut.set(true)
                runCatching { stream.close() }
            },
            deadline.minusNanos(System.nanoTime() - startedAt).toNanos().coerceAtLeast(0L),
            TimeUnit.NANOSECONDS,
        )
    try {
        val bytes = stream.use { it.readNBytes(maxBodyBytes + 1) }
        if (bytes.size > maxBodyBytes) throw BodyTooLargeException(maxBodyBytes = maxBodyBytes)
        return BoundedResponse(
            statusCode = response.statusCode(),
            headers = response.headers(),
            body = bytes.decodeToString(),
        )
    } catch (exception: IOException) {
        if (Thread.currentThread().isInterrupted || exception.cause is InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedException("Interrupted while reading the response body").apply { initCause(exception) }
        }
        if (timedOut.get()) throw BodyTimeoutException(deadline = deadline, cause = exception)
        throw exception
    } finally {
        watchdog.cancel(false)
    }
}

internal fun parseRetryAfter(value: String?): Duration? {
    val trimmed = value?.trim() ?: return null
    trimmed.toBigIntegerOrNull()?.let { seconds ->
        val bounded = seconds.coerceIn(BigInteger.ZERO, MAX_RETRY_AFTER.seconds.toBigInteger())
        return Duration.ofSeconds(bounded.toLong())
    }
    return runCatching { ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME) }
        .map { Duration.between(Instant.now(), it.toInstant()).coerceIn(Duration.ZERO, MAX_RETRY_AFTER) }
        .getOrNull()
}
