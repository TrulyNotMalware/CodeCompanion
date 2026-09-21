<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# test/kotlin/dev/notypie/application/mcp

## Purpose
Specs for the MCP server lane: `McpToolGate` (token → role → permission → audit wrapper around every tool body)
and `DomainReadTools` (the read-only tools the AI sidecar can call: `get_status`, `list_meetings`,
`list_roles`).

## Key Files
| File | Description |
|------|-------------|
| `McpToolGateTest.kt` | `McpToolGate.execute(transportContext, toolName, requiredPermission, argumentsSummary) { body }` with a MockK `CommandRoleResolver` and `McpToolCallHistoryRepository`. Cases: role lacks permission → `isError`, text names the tool and lowercase permission, `DENIED` audit row with resolved role and requester; permitted → body text returned, `COMPLETED` row carrying `turnId`, `sessionKey`, `argumentsJson`, `durationMs >= 0`; `McpTransportContext.EMPTY` → "Unauthenticated", resolver and audit never touched; body throws → `FAILED` row with `errorCode = "IllegalStateException"`; audit repository throws → tool result still succeeds; resolver throws → fails closed, `FAILED` row with floor role `USER`. |
| `DomainReadToolsTest.kt` | `DomainReadTools` built over a real `McpToolGate` (relaxed audit repository), a MockK `McpSyncRequestContext` whose `transportContext()` carries `createScopedTurnToken()` under `SCOPED_TURN_TOKEN_CONTEXT_KEY`, and `Clock.fixed(2026-07-08T03:00Z)`. `get_status`: `DEVELOPER` gets `OpsStatusService.renderReport()` verbatim, `USER` is denied ("permission"). `list_meetings`: null `daysAhead` → `getMeetingsByUserIdInRange(userId, now, now + 7d)`, cancelled meetings filtered out; `daysAhead = 99` → clamped to 31 and "No meetings". `list_roles`: `ADMIN` gets `RoleManagementService.renderGrants()`, `DEVELOPER` denied. |

Both files define an identical private `CallToolResult.text()` helper.

## For AI Agents

### Working In This Directory
- The token's `userId` is the only identity the gate trusts; stub `roleResolver.resolve(userId = token.userId)`
  exactly, not with `any()`, so a test proves the gate resolves the token's user and not the caller's argument.
- Audit rows are captured with `slot<McpToolCallRecord>()` inside `verify(exactly = 1)`; keep each capture and
  its assertions in one `then` — a slot holds only the last value.
- `McpSyncRequestContext` is a strict mock with a single stub; any new field the tools read from the request
  context must be stubbed in `DomainReadToolsTest` or MockK throws.
- New tools need both a gate-level permission case here and a `DomainReadToolsTest` case per role boundary.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.mcp.*'
```
Fixtures used: `testFixtures/.../application/security/mcp/ScopedTurnTokenCreator.kt` (`createScopedTurnToken`);
`domain` testFixtures `meet/MeetingDtoCreator.kt` (`createMeetingDto`).

### Common Patterns
- `gateWith(role, auditRepository)` / `toolsWith(role, ...)` local factories that wire a resolver returning the
  role under test; pass only the collaborator the case needs.
- Results are `CallToolResult`; assert `isError` first, then `text()`.

## Dependencies

### Internal
- `application/mcp/McpToolGate.kt`, `application/mcp/DomainReadTools.kt`
- `application/security/mcp/ScopedTurnToken`, `SCOPED_TURN_TOKEN_CONTEXT_KEY`
- `application/service/command/CommandRoleResolver`, `RoleManagementService`, `application/service/ops/OpsStatusService`
- `infrastructure/repository/mcp/*`, `infrastructure/repository/meeting/MeetingRepository`

### External
MCP Java SDK (`McpTransportContext`, `McpSchema.CallToolResult`), Spring AI MCP annotations, MockK, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
