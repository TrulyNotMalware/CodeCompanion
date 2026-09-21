<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/repository/agent/schema

## Purpose
JPA entities for the agent lane.

## Key Files
| File | Description |
|------|-------------|
| `AgentSessionSchema.kt` | `@Entity(name = "agent_session")`, `uk_agent_session_session_key` on `session_key` (160). `id` IDENTITY, `session_key`, `provider_session_id` (255, `var`), `created_at` (`@CreationTimestamp`), `updated_at` (`@UpdateTimestamp`) |
| `AgentTurnHistorySchema.kt` | `enum AgentTurnOutcome { COMPLETED, BUSY, FAILED }`; `@Entity(name = "agent_turn_history")` with indexes on `created_at` and `session_key`. Columns: `session_key` (160), `requester_id` (255), `channel` (255), `idempotency_key` (36), `outcome` `@Enumerated(STRING)` (16), `error_code?` (64), `input_tokens?`, `output_tokens?`, `duration_ms`, `created_at` |

## For AI Agents

### Working In This Directory
- **`providerSessionId` is the only mutable column** (`var`); everything on the history row is `val` —
  the table is append-only by construction.
- **`outcome` is stored by enum name with `length = 16`**; new `AgentTurnOutcome` constants must fit and
  must never be renamed once rows exist.
- **Token counts are nullable** because the sidecar's `done.usage` block is optional; treat `null` as
  "unknown", not zero, when aggregating.
- Entity name doubles as table name (no `@Table(name)`); the `@Table` annotation carries only constraints
  and indexes. Any column change needs a new `V*` migration under `application/src/main/resources/db/migration/`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.*'
```
No schema-level spec exists for this lane (see `../AGENTS.md`).

### Common Patterns
- `@field:` targeted Jakarta annotations on constructor `val`s; `id: Long = 0` with IDENTITY.
- Enum columns always `@Enumerated(EnumType.STRING)` with an explicit `length`.

## Dependencies

### Internal
None.

### External
Jakarta Persistence, Hibernate `@CreationTimestamp` / `@UpdateTimestamp`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
