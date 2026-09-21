<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/repository/mcp

## Purpose
Append-only audit of MCP tool calls made by the agent lane's model through this app's domain-tool
endpoint: who called which tool in which turn, with what resolved role, and how it ended.

## Key Files
| File | Description |
|------|-------------|
| `McpToolCallHistoryRepository.kt` | `data class McpToolCallRecord(toolName, requesterId, sessionKey, turnId, resolvedRole: UserRole, outcome: McpToolCallOutcome, errorCode?, argumentsJson?, durationMs)`; port `record(call)` |
| `McpToolCallHistoryRepositoryImpl.kt` | Maps the record to `McpToolCallHistorySchema` and saves. Final class, no `@Transactional` |
| `JpaMcpToolCallHistoryRepository.kt` | Bare `JpaRepository<McpToolCallHistorySchema, Long>` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `schema/` | `McpToolCallHistorySchema`, `McpToolCallOutcome` (see `schema/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **Identity comes from the per-turn scoped token, not from Slack.** `requesterId`, `sessionKey`, and
  `turnId` are copied from the token `McpToolGate` validated; `turnId` equals the agent turn's
  `idempotency_key`, which is how a tool call is traced back to `agent_turn_history`.
- **`argumentsJson` is capped at 2000 chars by the column, and nothing here truncates.** `McpToolGate`
  passes an `argumentsSummary`; keep that summary bounded or the insert fails and the audit row is lost.
- **`record` must not fail the tool call.** The gate treats this as best-effort audit; do not add
  validation that throws.
- Migration: `V13__add_mcp_tool_call_history_table.sql`. Bean: `JpaConfiguration.mcpToolCallHistoryRepository`;
  consumer: `application/mcp/McpToolGate`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.*'
```
No spec targets this lane in `:infrastructure`; `McpToolGateTest` in `:application` verifies the record
contents through a mocked port.

### Common Patterns
- Record data class in the port file; `*Impl` is a pure mapper over `save`.

## Dependencies

### Internal
- `domain/command/authorization/UserRole`
- `repository/mcp/schema`

### External
Spring Data JPA.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
