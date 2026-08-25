package dev.notypie.impl.agent

import dev.notypie.common.jsonMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.stream.Stream

private val log = KotlinLogging.logger {}

/**
 * [AgentGateway] adapter for the claude-sidecar HTTP+SSE contract (`POST /v1/converse`).
 *
 * The sidecar streams `session` → 0..N `text`/`tool_use`/`tool_result` → terminal `done`|`error`
 * as Server-Sent Events; this client folds the stream into a single terminal [AgentTurnResult].
 * Wire field names are camelCase per the sidecar's `openapi.yaml` (the contract source of truth).
 *
 * Timeout note: [requestTimeout] bounds the whole exchange from the client side as a safety net;
 * the turn ceiling itself is enforced server-side (`TURN_TIMEOUT_SEC` → terminal `error: timeout`),
 * so configure this comfortably above the sidecar's ceiling.
 */
class SidecarAgentClient(
    private val baseUrl: String,
    private val bearerSecret: String,
    private val requestTimeout: Duration = Duration.ofSeconds(120L),
) : AgentGateway {
    companion object {
        const val CONVERSE_PATH = "/v1/converse"
        internal const val ERROR_CODE_BUSY = "busy"
        internal const val ERROR_CODE_TRANSPORT = "transport_error"
        internal const val ERROR_CODE_INCOMPLETE_STREAM = "incomplete_stream"

        private const val EVENT_SESSION = "session"
        private const val EVENT_TEXT = "text"
        private const val EVENT_DONE = "done"
        private const val EVENT_ERROR = "error"
    }

    // Pinned to HTTP/1.1: the default (HTTP/2) sends an h2c upgrade on plain-http URLs, which
    // uvicorn rejects and then fails to read the request body (400 "body: Field required").
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5L))
            .build()

    override fun converse(request: AgentTurnRequest): AgentTurnResult =
        runCatching { execute(request = request) }
            .getOrElse { exception ->
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
                .header("Authorization", "Bearer $bearerSecret")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .apply { request.userId?.let { header("X-User-Id", it) } }
                // Header, not body: the sidecar's request schema forbids unknown fields, and a
                // header keeps the credential out of request-body logging.
                .apply { request.scopedToken?.let { header("X-Turn-Token", it) } }
                .POST(HttpRequest.BodyPublishers.ofString(toRequestBody(request = request)))
                .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines())
        return response.body().use { lines ->
            if (response.statusCode() != 200) {
                toErrorResult(statusCode = response.statusCode(), body = lines)
            } else {
                foldSseStream(lines = lines)
            }
        }
    }

    /** Serialized by hand-built map so null optionals are omitted — the sidecar forbids unknown/extra fields. */
    private fun toRequestBody(request: AgentTurnRequest): String {
        val body = mutableMapOf<String, String>("sessionKey" to request.sessionKey, "prompt" to request.prompt)
        request.sessionId?.let { body["sessionId"] = it }
        request.appendSystemPrompt?.let { body["appendSystemPrompt"] = it }
        return jsonMapper.writeValueAsString(body)
    }

    private fun toErrorResult(statusCode: Int, body: Stream<String>): AgentTurnResult {
        val raw = body.reduce("") { acc, line -> acc + line }
        val error =
            runCatching { jsonMapper.readValue(raw, SidecarError::class.java) }
                .getOrElse { SidecarError(code = "http_$statusCode", message = raw.take(500)) }
        return if (statusCode == 429 || error.code == ERROR_CODE_BUSY) {
            AgentTurnResult.Busy
        } else {
            AgentTurnResult.Failed(code = error.code, message = error.message)
        }
    }

    /**
     * Folds the SSE line stream into the terminal result. Frames are `event:`/`data:` line pairs
     * separated by blank lines; `:keep-alive` comment lines are ignored per the SSE spec. Text
     * deltas are accumulated only as a fallback — `done.finalText` is authoritative when present.
     */
    private fun foldSseStream(lines: Stream<String>): AgentTurnResult {
        var eventName = ""
        val dataLines = mutableListOf<String>()
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
            return terminal
        }

        for (line in lines.iterator()) {
            when {
                line.isEmpty() -> flushFrame()?.let { return it }

                line.startsWith(":") -> Unit // keep-alive comment

                line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()

                line.startsWith("data:") -> dataLines.add(line.removePrefix("data:").trimStart())
            }
        }
        // A stream may close right after the terminal frame's data line without a trailing blank
        // line — flush so that frame still dispatches before we call the stream incomplete.
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

            EVENT_TEXT -> accumulatedText.append(jsonMapper.readValue(data, SidecarText::class.java).delta)

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

            // tool_use / tool_result relay only progress metadata — nothing to fold into the result.
            else -> log.debug { "Ignoring sidecar SSE event '$eventName'" }
        }
        return null
    }
}

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
