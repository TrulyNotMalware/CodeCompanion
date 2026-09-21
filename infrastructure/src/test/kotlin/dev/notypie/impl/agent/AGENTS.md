<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/src/test/kotlin/dev/notypie/impl/agent

## Purpose
Spec for the AI sidecar HTTP/SSE client in main `impl/agent/`. Runs against a real JDK `HttpServer` bound to
an ephemeral loopback port that speaks the sidecar's wire format, so request headers, body shape, and the
event-stream state machine are all exercised for real without Spring or MockK.

## Key Files
| File | Description |
|------|-------------|
| `SidecarAgentClientTest.kt` | `SidecarAgentClient.converse(request: AgentTurnRequest): AgentTurnResult`. Server stub handles `/v1/converse`, captures body and the `Authorization`, `X-User-Id`, `X-Turn-Token` headers, then replies via a spec-level `respond` lambda reassigned per `given`. Cases: full `session → text → tool_use → tool_result → text → done` stream (with a `: keep-alive` comment) → `Completed(finalText, sessionId, inputTokens, outputTokens)`; `Bearer <secret>` + `X-User-Id` sent; no `X-Turn-Token` without a `scopedToken`; a `scopedToken` travels only as `X-Turn-Token` and never in the body; `sessionId` / `appendSystemPrompt` are omitted entirely (not `null`) on a first turn; HTTP 429 before streaming → `Busy`; terminal `error` frame → `Failed(code, message)`; `error` with `code = busy` → `Busy`; stream ending with no terminal frame → `Failed(ERROR_CODE_INCOMPLETE_STREAM)`; connection refused (`127.0.0.1:1`) → `Failed(ERROR_CODE_TRANSPORT)` instead of throwing. Plain `BehaviorSpec`; server stopped in `afterSpec`. |

## For AI Agents

### Working In This Directory
- The SSE bodies in the spec are the contract: camelCase field names (`sessionId`, `finalText`,
  `inputTokens`, `toolUseId`) per the sidecar's `openapi.yaml`. Change the client and the stub bodies
  together; a mismatch here means a mismatch in production.
- `respond`, `capturedBody`, and the captured headers are spec-level `var`s assigned inside each `given`.
  This works because Kotest runs the `given` blocks of one spec sequentially — do not enable per-spec
  concurrency for this file.
- The client must never throw out of `converse`; every failure mode is an `AgentTurnResult`. Add a case here
  for any new terminal event or HTTP status the sidecar can return.
- The bearer value and session ids in the spec are placeholders, not credentials.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.impl.agent.*'
```
Needs a free loopback port (port `0` bind) and, for the transport-error case, nothing listening on
`127.0.0.1:1`.

### Common Patterns
`HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)` + `createContext(path) { exchange -> … }`, with
`sseResponse(body)` / `jsonResponse(status, body)` helpers producing the `(HttpExchange) -> Unit` handler.
The same stub shape is used by `impl/cve/`.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/impl/agent/` — `SidecarAgentClient`, `AgentTurnRequest`,
  `AgentTurnResult`
- `infrastructure/src/main/kotlin/dev/notypie/common/jsonMapper` — to parse the captured request body

### External
JDK `com.sun.net.httpserver`, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
