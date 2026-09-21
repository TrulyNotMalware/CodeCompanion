<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/repository/mcp/schema

## Purpose
JPA entity and outcome enum for the MCP tool-call audit table.

## Key Files
| File | Description |
|------|-------------|
| `McpToolCallHistorySchema.kt` | `enum McpToolCallOutcome { COMPLETED, DENIED, FAILED }`; `@Entity(name = "mcp_tool_call_history")` with indexes on `created_at` and `requester_id`. Columns: `tool_name` (64), `requester_id` (255), `session_key` (160), `turn_id` (36), `resolved_role: UserRole` `@Enumerated(STRING)` (16), `outcome` `@Enumerated(STRING)` (16), `error_code?` (64), `arguments_json?` (2000), `duration_ms`, `created_at` |

## For AI Agents

### Working In This Directory
- **Every column is `val`** — the table is append-only; there is no `updated_at`.
- **`resolved_role` is 16 chars wide while `user_command_role.role` is 32.** Both store the same
  `UserRole` enum by name, so this column is the binding limit on constant-name length.
- **`DENIED` is a distinct outcome** from `FAILED`: the gate records role denials so they are auditable
  without an error code.
- `turn_id` has no FK to `agent_turn_history`; the join is by value in reporting queries only.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.*'
```
No schema-level spec exists (see `../AGENTS.md`).

### Common Patterns
- `@field:` Jakarta annotations, IDENTITY id, `@Enumerated(EnumType.STRING)` with explicit `length`.

## Dependencies

### Internal
- `domain/command/authorization/UserRole`

### External
Jakarta Persistence, Hibernate `@CreationTimestamp`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
