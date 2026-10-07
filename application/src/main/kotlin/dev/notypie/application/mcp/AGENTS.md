<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-07 -->

# application/mcp

## Purpose
The MCP tool surface the AI agent lane can call back into: seven read-only domain tools
(`get_status`, `list_meetings`, `list_roles`, `list_standups`, `list_cve_subscriptions`, `cve_latest`, `get_ai_usage`) and the
single gate every tool call passes through.
Identity always comes from the verified per-turn token in the `McpTransportContext`; the gate
re-resolves the caller's role on every call, audits the call, and converts denials and failures into
MCP error results so the model can relay them and the turn survives.

Both classes are explicit `@Bean`s in `configurations/McpServerConfiguration`, which loads only when
`slack.app.mcp.enabled=true`; the Spring AI starter registers the `@McpTool` methods from the
`DomainReadTools` bean.

## Key Files
| File | Description |
|------|-------------|
| `McpToolGate.kt` | `class McpToolGate(commandRoleResolver, mcpToolCallHistoryRepository)`. `execute(transportContext, toolName, requiredPermission, argumentsSummary = null, body: (ScopedTurnToken) -> String): CallToolResult` — reads the token under `SCOPED_TURN_TOKEN_CONTEXT_KEY` (missing → "Unauthenticated tool call." error), resolves the role via `CommandRoleResolver.resolution(userId)`, checks `role.grants(permission)`, runs `body`, and writes one `McpToolCallRecord` (`COMPLETED` / `DENIED` / `FAILED`, duration, `argumentsJson`) per call. A denial whose role is the resolver's lookup-failure fallback (`lookupFailed`) is answered as an execution failure ("failed to execute. Try again…") and audited `FAILED` with `errorCode = "RoleLookupFailed"`, so a role-store outage never reads as an ADMIN being refused; a fallback `USER` still runs tools `USER` may use. Audit writes are `runCatching` — they never fail the tool |
| `DomainReadTools.kt` | `class DomainReadTools(mcpToolGate, opsStatusService, roleManagementService, meetingRepository, standupRepository, cveSubscriptionService, cveLatestQueryService, agentUsageReportService, appConfig, clock)`. `@McpTool get_status` (`OPERATIONS`) → `OpsStatusService.renderReport()`; `@McpTool get_ai_usage(days: Int?)` (`OPERATIONS`, window coerced to `1..90`, default 7, `argumentsSummary` `{"days":N}`) → `AgentUsageReportService.renderReport(days)`; `@McpTool list_meetings(daysAhead: Int?)` (`BASIC`, window coerced to `1..31`, default 7) → `meetingRepository.getMeetingsByUserIdInRange(userId = token.userId, ...)` rendered as `• title — yyyy-MM-dd HH:mm (host <@id>, N participant(s))`, canceled meetings dropped; `@McpTool list_roles` (`ADMINISTRATION`) → `RoleManagementService.renderGrants()`; `@McpTool list_standups` (`BASIC`) → `standupRepository.listActiveRoutines()` filtered in memory to routines whose `creatorId` or a member's `userId` is `token.userId`, rendered as `• name — HH:mm zone, Mon/Tue/…, cutoff +Nm, N member(s), channel <#id>, summary <#id>, created by <@id>`, or "You are not in any active standup routine."; `@McpTool list_cve_subscriptions` (`BASIC`) → `CveSubscriptionService.renderSubscriptions(userId = token.userId)`; `@McpTool cve_latest(topicKey: String?)` (`BASIC`, blank key treated as omitted, `argumentsSummary` `{"topicKey":"key"}` with `"` replaced by `'`, or `{"topicKey":null}`) → `CveLatestQueryService.renderLatest(userId = token.userId, topicKey)`. Both CVE tools answer `CVE_FEATURE_DISABLED_MESSAGE` (from `service/cve/ops`) without calling the service while `appConfig.cve.enabled` is false. Meeting titles and routine names in `list_meetings` / `list_standups` output go through `domain/common/escapeMarkup()`: the model often echoes tool text, and the AI reply path only neutralises `<!…>` broadcasts, so an unescaped `<https://evil|label>` title would reach Slack as a disguised link. |

## For AI Agents

### Working In This Directory
- Tools never take a user id as an argument — a model could invent one. The subject is always
  `token.userId` from the verified `ScopedTurnToken`; new tools must follow the same shape and route
  through `mcpToolGate.execute { token -> ... }`.
- The token carries no role on purpose. `McpToolGate` calls `CommandRoleResolver.resolve` on every
  invocation so a `@bot revoke` mid-conversation takes effect on the next tool call. Never cache the
  role or move the check into the token claims. The resolver caches only `USER` answers
  (`CommandRoleResolver.CACHE_TTL` 60 s per JVM), so a revoke applies to the next tool call on every
  replica; only a grant can lag up to 60 s on a replica that still caches the user as `USER`.
- Pass `argumentsSummary` for any tool with parameters (`list_meetings` sends
  `{"daysAhead":N}`); it is what lands in `mcp_tool_call_history.arguments_json`.
- Error text returned to the model is deliberately generic ("`tool` failed to execute...").
  Details go to the log, never into the `CallToolResult`.
- A missing token inside `execute` means a wiring regression — `security/mcp/McpTurnTokenFilter`
  already rejected anonymous requests and the transport provider's `contextExtractor` in
  `McpServerConfiguration` is what puts the token into the context. Fix the wiring, do not relax the
  gate.
- `DomainReadTools.clock` is not passed by the bean method, so `list_meetings` uses the system zone
  while `CommandRoleResolver` / `OpsStatusService` / `AgentUsageReportService` are the same beans chat uses. Keep
  `renderReport()` and `renderGrants()` shared so `@bot status` / `@bot usage` / `@bot roles` and the tools never diverge; the same goes for
  `renderSubscriptions()` (`/subscriptions`) and `renderLatest()` (`/latest`).
- The CVE render methods do not check `appConfig.cve.enabled` (their event listeners do), so each CVE tool
  checks it before delegating. Keep that check when adding another CVE tool.
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
- `application/service/agent/AgentUsageReportService` — `renderReport(days)`
- `application/configurations/McpServerConfiguration` — bean declarations, filter, context extractor
- `infrastructure/repository/mcp/` — `McpToolCallHistoryRepository`, `McpToolCallRecord`, `McpToolCallOutcome`
- `infrastructure/repository/meeting/MeetingRepository` — `getMeetingsByUserIdInRange`
- `infrastructure/repository/standup/StandupRepository` — `listActiveRoutines`
- `application/service/cve/subscription/CveSubscriptionService` — `renderSubscriptions(userId)`;
  `application/service/cve/query/CveLatestQueryService` — `renderLatest(userId, topicKey)`;
  `application/service/cve/ops/CVE_FEATURE_DISABLED_MESSAGE`; `application/configurations/AppConfig` — `cve.enabled`
- `domain/command/authorization/` — `CommandPermission`, `UserRole.grants`
- `domain/meet/dto/MeetingDto`, `domain/standup/dto/RoutineDto`

### External
Spring AI MCP server annotations (`@McpTool`, `@McpToolParam`, `McpSyncRequestContext`), MCP Java SDK
(`McpSchema.CallToolResult`, `McpTransportContext`), kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
