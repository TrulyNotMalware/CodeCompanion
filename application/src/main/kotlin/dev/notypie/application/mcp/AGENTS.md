<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# application/mcp

## Purpose
The MCP tool surface the AI agent lane can call back into: three read-only domain tools
(`get_status`, `list_meetings`, `list_roles`) and the single gate every tool call passes through.
Identity always comes from the verified per-turn token in the `McpTransportContext`; the gate
re-resolves the caller's role on every call, audits the call, and converts denials and failures into
MCP error results so the model can relay them and the turn survives.

Both classes are explicit `@Bean`s in `configurations/McpServerConfiguration`, which loads only when
`slack.app.mcp.enabled=true`; the Spring AI starter registers the `@McpTool` methods from the
`DomainReadTools` bean.

## Key Files
| File | Description |
|------|-------------|
| `McpToolGate.kt` | `class McpToolGate(commandRoleResolver, mcpToolCallHistoryRepository)`. `execute(transportContext, toolName, requiredPermission, argumentsSummary = null, body: (ScopedTurnToken) -> String): CallToolResult` — reads the token under `SCOPED_TURN_TOKEN_CONTEXT_KEY` (missing → "Unauthenticated tool call." error), resolves `UserRole` via `CommandRoleResolver.resolve(userId)`, checks `role.grants(permission)`, runs `body`, and writes one `McpToolCallRecord` (`COMPLETED` / `DENIED` / `FAILED`, duration, `argumentsJson`) per call. Audit writes are `runCatching` — they never fail the tool |
| `DomainReadTools.kt` | `class DomainReadTools(mcpToolGate, opsStatusService, roleManagementService, meetingRepository, clock = Clock.systemDefaultZone())`. `@McpTool get_status` (`OPERATIONS`) → `OpsStatusService.renderReport()`; `@McpTool list_meetings(daysAhead: Int?)` (`BASIC`, window coerced to `1..31`, default 7) → `meetingRepository.getMeetingsByUserIdInRange(userId = token.userId, ...)` rendered as `• title — yyyy-MM-dd HH:mm (host <@id>, N participant(s))`, canceled meetings dropped; `@McpTool list_roles` (`ADMINISTRATION`) → `RoleManagementService.renderGrants()` |

## For AI Agents

### Working In This Directory
- Tools never take a user id as an argument — a model could invent one. The subject is always
  `token.userId` from the verified `ScopedTurnToken`; new tools must follow the same shape and route
  through `mcpToolGate.execute { token -> ... }`.
- The token carries no role on purpose. `McpToolGate` calls `CommandRoleResolver.resolve` on every
  invocation so a `@bot revoke` mid-conversation takes effect on the next tool call. Never cache the
  role or move the check into the token claims.
- Pass `argumentsSummary` for any tool with parameters (`list_meetings` sends
  `{"daysAhead":N}`); it is what lands in `mcp_tool_call_history.arguments_json`.
- Error text returned to the model is deliberately generic ("`tool` failed to execute...").
  Details go to the log, never into the `CallToolResult`.
- A missing token inside `execute` means a wiring regression — `security/mcp/McpTurnTokenFilter`
  already rejected anonymous requests and the transport provider's `contextExtractor` in
  `McpServerConfiguration` is what puts the token into the context. Fix the wiring, do not relax the
  gate.
- `DomainReadTools.clock` is not passed by the bean method, so `list_meetings` uses the system zone
  while `CommandRoleResolver` / `OpsStatusService` are the same beans chat uses. Keep `renderReport()`
  and `renderGrants()` shared so `@bot status` / `@bot roles` and the tools never diverge.
- Adding a tool: `@McpTool` method here, a `CommandPermission` level, a `DomainReadToolsTest` case for
  allowed + denied, and the `scripts/mcp-smoke.sh` call list if it should be smoke-tested.

### Testing Requirements
```bash
./gradlew :application:test --tests '*McpToolGateTest*' --tests '*DomainReadToolsTest*'
```
Both specs are Kotest `BehaviorSpec` + MockK. Build the identity with `createScopedTurnToken()` from
`src/testFixtures/kotlin/dev/notypie/application/security/mcp/ScopedTurnTokenCreator.kt` and the
context with `McpTransportContext.create(mapOf(SCOPED_TURN_TOKEN_CONTEXT_KEY to token))`; an empty
context (`McpTransportContext.EMPTY`) is the unauthenticated case. Assert the audit record's
`outcome` for every branch (completed, denied, body threw, role resolution threw) and that a failing
`record` does not change the tool result. Live end-to-end: `./scripts/mcp-smoke.sh` against a running
app with MCP enabled.

### Common Patterns
- One `@McpTool` method per tool, single-expression body delegating to `mcpToolGate.execute(...)`.
- `CallToolResult.builder().addTextContent(text).isError(bool).build()` for both success and error;
  tools return plain text, not structured content.
- Kotlin named parameters at every call; `private const` defaults (`DEFAULT_DAYS_AHEAD`) and file-level
  `DateTimeFormatter`s.

## Dependencies

### Internal
- `application/security/mcp/` — `ScopedTurnToken`, `SCOPED_TURN_TOKEN_CONTEXT_KEY`
- `application/service/command/` — `CommandRoleResolver`, `RoleManagementService.renderGrants()`
- `application/service/ops/OpsStatusService` — `renderReport()`
- `application/configurations/McpServerConfiguration` — bean declarations, filter, context extractor
- `infrastructure/repository/mcp/` — `McpToolCallHistoryRepository`, `McpToolCallRecord`, `McpToolCallOutcome`
- `infrastructure/repository/meeting/MeetingRepository` — `getMeetingsByUserIdInRange`
- `domain/command/authorization/` — `CommandPermission`, `UserRole.grants`
- `domain/meet/dto/MeetingDto`

### External
Spring AI MCP server annotations (`@McpTool`, `@McpToolParam`, `McpSyncRequestContext`), MCP Java SDK
(`McpSchema.CallToolResult`, `McpTransportContext`), kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
