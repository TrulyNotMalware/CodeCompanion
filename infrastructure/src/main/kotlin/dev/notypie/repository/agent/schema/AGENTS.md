<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-26 -->

# infrastructure/repository/agent/schema

## Purpose
JPA entities for the agent lane.

## Key Files
| File | Description |
|------|-------------|
| `AgentSessionSchema.kt` | `@Entity(name = "agent_session")`, `uk_agent_session_session_key` on `session_key` (160). `id` IDENTITY, `session_key`, `provider_session_id` (255, `val` — rows are written only by the native upsert in `JpaAgentSessionRepository`), `created_at` (`@CreationTimestamp`), `updated_at` (`@UpdateTimestamp`); the upsert sets both timestamps from the caller's `now`, so the annotations apply only to a JPA insert, which nothing does today |
| `AgentTurnHistorySchema.kt` | `enum AgentTurnOutcome { COMPLETED, BUSY, FAILED }`; `@Entity(name = "agent_turn_history")` with indexes on `created_at` and `session_key`. Columns: `session_key` (160), `requester_id` (255), `channel` (255), `idempotency_key` (36), `outcome` `@Enumerated(STRING)` (16), `error_code?` (64), `input_tokens?`, `output_tokens?`, `duration_ms`, `created_at` |
| `AgentAskTurnSchema.kt` | `enum AgentAskTurnStatus { PENDING, IN_PROGRESS, COMPLETED, ABANDONED }`; `@Entity(name = "agent_ask_turn") : Persistable<String>` with index `idx_agent_ask_turn_status_updated_at` on `(status, updated_at)`. Columns, all `val`: `event_id` (36, the id), `message_ts` (32), `status` `@Enumerated(STRING)` (16, default `PENDING`), `attempt_count` and `run_count` (`INT NOT NULL DEFAULT 0`), `created_at` (not updatable) and `updated_at`, both non-null and taken from the constructor. A transient `newRow` flag, cleared in `@PostPersist` / `@PostLoad`, makes `save` insert an application-assigned id without a `SELECT` (the `OutboxMessage` pattern) |

## For AI Agents

### Working In This Directory
- **A ledger row changes only through the native statements in `AgentAskTurnRepository`.** The entity is the
  insert shape and the read shape; nothing loads it, mutates it and saves it. Its timestamps carry no
  `@CreationTimestamp` / `@UpdateTimestamp` because the caller's clock writes them (`../AGENTS.md`).
- **No column of `AgentSessionSchema` or `AgentTurnHistorySchema` is mutable** (every property is `val`);
  `provider_session_id` changes only through the native upsert in `JpaAgentSessionRepository`, and the
  history table is append-only by construction.
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
No schema-level spec exists for this lane (see `../AGENTS.md`); `AgentAskTurnRepositoryTest` reads the ledger
entity back through `findById` and `findResumable`, which covers its mapping, the enum column included.

### Common Patterns
- `@field:` targeted Jakarta annotations on constructor `val`s; `id: Long = 0` with IDENTITY.
- Enum columns always `@Enumerated(EnumType.STRING)` with an explicit `length`.

## Dependencies

### Internal
None.

### External
Jakarta Persistence, Hibernate `@CreationTimestamp` / `@UpdateTimestamp`, Spring Data `Persistable`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
