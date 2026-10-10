<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-07 -->

# infrastructure/repository/mcp

## Purpose
Append-only audit of MCP tool calls made by the agent lane's model through this app's domain-tool
endpoint: who called which tool in which turn, with what resolved role, and how it ended.

## Key Files
| File | Description |
|------|-------------|
| `McpToolCallHistoryRepository.kt` | `data class McpToolCallRecord(toolName, requesterId, sessionKey, turnId, resolvedRole: UserRole, outcome: McpToolCallOutcome, errorCode?, argumentsJson?, durationMs)`; aggregate row `ToolCallUsage(toolName, outcome, calls: Long)`; port `record(call)`, `countByToolSince(since)` |
| `McpToolCallHistoryRepositoryImpl.kt` | Maps the record to `McpToolCallHistorySchema` and saves; `countByToolSince` delegates. Final class, no `@Transactional` |
| `JpaMcpToolCallHistoryRepository.kt` | `JpaRepository<McpToolCallHistorySchema, Long>` + JPQL `countByToolSince(since)`: constructor expression over `created_at >= :since`, `GROUP BY toolName, outcome ORDER BY toolName, outcome` |

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
  consumers: `application/mcp/McpToolGate` (writes) and `application/service/agent/AgentUsageReportService` (aggregate).

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.*'
```
`McpToolCallHistoryRepositoryImplTest` (`@DataJpaTest`, H2 `MODE=MariaDB`) pins `countByToolSince`; `McpToolGateTest`
in `:application` verifies the record contents through a mocked port.

### Common Patterns
- Record and aggregate data classes in the port file; `*Impl` is a pure mapper over `save` and the query.

## Dependencies

### Internal
- `domain/command/authorization/UserRole`
- `repository/mcp/schema`

### External
Spring Data JPA.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
