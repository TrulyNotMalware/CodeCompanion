<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-22 -->

# infrastructure/impl/agent

## Purpose
The port to the AI backend (the claude-sidecar co-process) and its single adapter. `AgentGateway` is
deliberately one blocking turn in, one terminal result out; `SidecarAgentClient` speaks the sidecar's
`POST /v1/converse` HTTP + Server-Sent-Events contract and folds the stream into that result.

## Key Files
| File | Description |
|------|-------------|
| `AgentGateway.kt` | `interface AgentGateway { fun converse(request: AgentTurnRequest): AgentTurnResult }`; `AgentTurnRequest(sessionKey, prompt, sessionId?, userId?, appendSystemPrompt?, scopedToken?)`; `sealed interface AgentTurnResult` with `Completed(sessionId?, finalText, inputTokens?, outputTokens?)`, `data object Busy`, `Failed(code, message)` |
| `SidecarAgentClient.kt` | `class SidecarAgentClient(baseUrl, bearerSecret, requestTimeout = 120s) : AgentGateway`. JDK `HttpClient` pinned to HTTP/1.1 with a 5 s connect timeout. Headers: `Authorization: Bearer`, `Accept: text/event-stream`, optional `X-User-Id`, `X-Turn-Token`. `requestTimeout` bounds the **whole turn**: `HttpRequest.timeout` covers the headers and a shared daemon watchdog closes the body stream when the remaining budget runs out (`Failed(stream_timeout)`), so a sidecar that stalls mid-stream cannot pin the relay thread. Non-200 bodies are capped at 8 KiB. Private wire DTOs `SidecarSession`, `SidecarText`, `SidecarDone(finalText, usage)`, `SidecarUsage`, `SidecarError(code, message)`; error codes `busy`, `transport_error`, `incomplete_stream`, `stream_timeout` |

## For AI Agents

### Working In This Directory
- **HTTP/1.1 is pinned on purpose.** The JDK default (HTTP/2) sends an h2c upgrade on plain-http URLs,
  which uvicorn rejects and then fails to read the body (`400 "body: Field required"`).
- **The request body is a hand-built map** so absent optionals are omitted — the sidecar's schema
  forbids unknown or extra fields. `userId` and `scopedToken` travel as headers, never in the body, so the
  per-turn MCP credential stays out of request-body logging.
- **Busy has two shapes:** HTTP 429 (or a non-200 body whose `code == "busy"`) and an SSE `error` frame
  with `code == "busy"`. Both map to `AgentTurnResult.Busy`; an unparseable non-200 body becomes
  `Failed(code = "http_<status>", message = first 500 chars)`.
- **`done.finalText` is authoritative;** accumulated `text` deltas are only the fallback when it is blank.
  `tool_use` / `tool_result` frames are ignored (debug log). A stream that ends without a terminal frame
  yields `Failed(incomplete_stream)`; a final frame with no trailing blank line is still flushed.
- **`converse` never throws.** Transport exceptions become `Failed(transport_error)`; the caller
  (`AgentConverseService`, `SidecarAiSummarizer`) branches on the sealed result.
- Wired by `application/configurations/AgentConfiguration.kt`; the sidecar's `openapi.yaml` is the field-name
  source of truth (camelCase: `sessionKey`, `sessionId`, `appendSystemPrompt`, `finalText`, `inputTokens`).

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.agent.SidecarAgentClientTest'
```
The spec drives the client against a local HTTP server emitting hand-written SSE frames; add a case per
new frame type or error code rather than mocking `HttpClient`.

### Common Patterns
- Port and adapter side by side; result modelled as a sealed interface, consumed with exhaustive `when`.
- `runCatching { ... }.getOrElse { ... }` at the transport boundary; `jsonMapper` (`common/`) for all JSON.

## Dependencies

### Internal
- `common/JsonMapper.kt` — `jsonMapper`

### External
JDK `java.net.http`, Jackson 3 (`tools.jackson`), `kotlin-logging`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
