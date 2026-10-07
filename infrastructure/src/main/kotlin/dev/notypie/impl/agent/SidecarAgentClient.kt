package dev.notypie.impl.agent

import dev.notypie.common.jsonMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.BufferedReader
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

class SidecarAgentClient(
    private val baseUrl: String,
    private val bearerSecret: String,
    private val requestTimeout: Duration = Duration.ofSeconds(120L),
    private val maxFrameChars: Int = DEFAULT_MAX_FRAME_CHARS,
    private val maxTextChars: Int = DEFAULT_MAX_TEXT_CHARS,
) : AgentGateway {
    companion object {
        const val CONVERSE_PATH = "/v1/converse"
        internal const val ERROR_CODE_BUSY = "busy"
        internal const val ERROR_CODE_TRANSPORT = "transport_error"
        internal const val ERROR_CODE_INCOMPLETE_STREAM = "incomplete_stream"
        internal const val ERROR_CODE_STREAM_TIMEOUT = "stream_timeout"
        internal const val ERROR_CODE_STREAM_TOO_LARGE = "stream_too_large"
        const val DEFAULT_MAX_FRAME_CHARS = 512 * 1024
        const val DEFAULT_MAX_TEXT_CHARS = 256 * 1024
        private const val MAX_ERROR_BODY_CHARS = 8_192

        private val watchdog: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "sidecar-stream-watchdog").apply { isDaemon = true }
            }

        private const val EVENT_SESSION = "session"
        private const val EVENT_TEXT = "text"
        private const val EVENT_DONE = "done"
        private const val EVENT_ERROR = "error"
    }

    // Pinned to HTTP/1.1: default HTTP/2 sends an h2c upgrade that uvicorn rejects (400 "body: Field required").
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5L))
            .build()

    override fun converse(request: AgentTurnRequest): AgentTurnResult =
        try {
            execute(request = request)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } catch (exception: Exception) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Sidecar turn interrupted while streaming").apply { initCause(exception) }
            }
            log.error(exception) { "Sidecar converse transport failure sessionKey=${request.sessionKey}" }
            AgentTurnResult.Failed(
                code = ERROR_CODE_TRANSPORT,
                message = exception.message ?: exception::class.java.simpleName,
            )
        }

    private fun execute(request: AgentTurnRequest): AgentTurnResult {
        val httpRequest =
            HttpRequest
                .newBuilder(URI.create("$baseUrl$CONVERSE_PATH"))
                .timeout(requestTimeout)
                .apply { if (bearerSecret.isNotBlank()) header("Authorization", "Bearer $bearerSecret") }
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .apply { request.userId?.let { header("X-User-Id", it) } }
                .apply { request.scopedToken?.let { header("X-Turn-Token", it) } }
                .POST(HttpRequest.BodyPublishers.ofString(toRequestBody(request = request)))
                .build()

        val startedAt = System.nanoTime()
        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
        val remaining = requestTimeout.minusNanos(System.nanoTime() - startedAt)
        return withStreamDeadline(stream = response.body(), deadline = remaining) { reader ->
            try {
                if (response.statusCode() != 200) {
                    toErrorResult(statusCode = response.statusCode(), body = reader)
                } else {
                    foldSseStream(reader = reader)
                }
            } catch (exception: SseLimitExceededException) {
                log.warn {
                    "Sidecar stream exceeded a size bound sessionKey=${request.sessionKey}: ${exception.message}"
                }
                AgentTurnResult.Failed(code = ERROR_CODE_STREAM_TOO_LARGE, message = exception.message.orEmpty())
            }
        }
    }

    private fun withStreamDeadline(
        stream: InputStream,
        deadline: Duration,
        block: (BufferedReader) -> AgentTurnResult,
    ): AgentTurnResult {
        val timedOut = AtomicBoolean(false)
        val task =
            watchdog.schedule(
                {
                    timedOut.set(true)
                    runCatching { stream.close() }
                },
                deadline.toNanos().coerceAtLeast(0L),
                TimeUnit.NANOSECONDS,
            )
        try {
            return stream.bufferedReader(charset = Charsets.UTF_8).use(block)
        } catch (exception: Exception) {
            if (!timedOut.get()) throw exception
            return AgentTurnResult.Failed(
                code = ERROR_CODE_STREAM_TIMEOUT,
                message = "SSE stream exceeded ${requestTimeout.toSeconds()}s without a terminal event",
            )
        } finally {
            task.cancel(false)
        }
    }

    private fun toRequestBody(request: AgentTurnRequest): String {
        val body = mutableMapOf<String, String>("sessionKey" to request.sessionKey, "prompt" to request.prompt)
        request.sessionId?.let { body["sessionId"] = it }
        request.appendSystemPrompt?.let { body["appendSystemPrompt"] = it }
        return jsonMapper.writeValueAsString(body)
    }

    private fun toErrorResult(statusCode: Int, body: BufferedReader): AgentTurnResult {
        val buffer = CharArray(MAX_ERROR_BODY_CHARS)
        var length = 0
        while (length < buffer.size) {
            val read = body.read(buffer, length, buffer.size - length)
            if (read < 0) break
            length += read
        }
        val raw = String(buffer, 0, length)
        val error =
            runCatching { jsonMapper.readValue(raw, SidecarError::class.java) }
                .getOrElse { SidecarError(code = "http_$statusCode", message = raw.take(500)) }
        return if (statusCode == 429 || error.code == ERROR_CODE_BUSY) {
            AgentTurnResult.Busy
        } else {
            AgentTurnResult.Failed(code = error.code, message = error.message)
        }
    }

    private fun foldSseStream(reader: BufferedReader): AgentTurnResult {
        var eventName = ""
        val dataLines = mutableListOf<String>()
        var frameChars = 0
        var sessionId: String? = null
        val accumulatedText = StringBuilder()

        fun flushFrame(): AgentTurnResult? {
            if (eventName.isEmpty() && dataLines.isEmpty()) return null
            val terminal =
                dispatchFrame(
                    eventName = eventName,
                    data = dataLines.joinToString(separator = "\n"),
                    sessionId = sessionId,
                    accumulatedText = accumulatedText,
                    onSession = { sessionId = it },
                )
            eventName = ""
            dataLines.clear()
            frameChars = 0
            return terminal
        }

        val lines = SseLineReader(reader = reader)
        while (true) {
            val line = lines.next(maxChars = maxFrameChars - frameChars) ?: break
            when {
                line.isEmpty() -> flushFrame()?.let { return it }

                line.startsWith(":") -> Unit

                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()

                line.startsWith("data:") -> {
                    dataLines.add(line.removePrefix("data:").trimStart())
                    frameChars += line.length
                }
            }
        }
        flushFrame()?.let { return it }
        return AgentTurnResult.Failed(
            code = ERROR_CODE_INCOMPLETE_STREAM,
            message = "SSE stream ended without a terminal done/error event",
        )
    }

    private fun dispatchFrame(
        eventName: String,
        data: String,
        sessionId: String?,
        accumulatedText: StringBuilder,
        onSession: (String?) -> Unit,
    ): AgentTurnResult? {
        when (eventName) {
            EVENT_SESSION -> onSession(jsonMapper.readValue(data, SidecarSession::class.java).sessionId)

            EVENT_TEXT -> {
                val delta = jsonMapper.readValue(data, SidecarText::class.java).delta
                if (accumulatedText.length + delta.length > maxTextChars) {
                    throw SseLimitExceededException(message = "accumulated text exceeds $maxTextChars chars")
                }
                accumulatedText.append(delta)
            }

            EVENT_DONE -> {
                val done = jsonMapper.readValue(data, SidecarDone::class.java)
                return AgentTurnResult.Completed(
                    sessionId = sessionId,
                    finalText = done.finalText.ifBlank { accumulatedText.toString() },
                    inputTokens = done.usage?.inputTokens,
                    outputTokens = done.usage?.outputTokens,
                )
            }

            EVENT_ERROR -> {
                val error = jsonMapper.readValue(data, SidecarError::class.java)
                return if (error.code == ERROR_CODE_BUSY) {
                    AgentTurnResult.Busy
                } else {
                    AgentTurnResult.Failed(code = error.code, message = error.message)
                }
            }

            else -> log.debug { "Ignoring sidecar SSE event '$eventName'" }
        }
        return null
    }

    private inner class SseLineReader(
        private val reader: BufferedReader,
    ) {
        private var skipLineFeed = false

        fun next(maxChars: Int): String? {
            val line = StringBuilder()
            while (true) {
                val char = reader.read()
                if (skipLineFeed) {
                    skipLineFeed = false
                    if (char == '\n'.code) continue
                }
                when (char) {
                    -1 -> return if (line.isEmpty()) null else line.toString()

                    '\n'.code -> return line.toString()

                    '\r'.code -> {
                        skipLineFeed = true
                        return line.toString()
                    }
                }
                if (line.length >= maxChars) {
                    throw SseLimitExceededException(message = "SSE frame exceeds $maxFrameChars chars")
                }
                line.append(char.toChar())
            }
        }
    }
}

private class SseLimitExceededException(
    message: String,
) : RuntimeException(message)

private data class SidecarSession(
    val sessionId: String? = null,
)

private data class SidecarText(
    val delta: String = "",
)

private data class SidecarDone(
    val finalText: String = "",
    val usage: SidecarUsage? = null,
)

private data class SidecarUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadInputTokens: Long? = null,
    val cacheCreationInputTokens: Long? = null,
)

private data class SidecarError(
    val code: String = "unknown",
    val message: String = "",
)
