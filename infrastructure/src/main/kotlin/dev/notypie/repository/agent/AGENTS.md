<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-13 -->

# infrastructure/repository/agent

## Purpose
Persistence for the AI-agent lane: `agent_session` maps a Slack conversation key to the sidecar's own
session id so a thread resumes as one conversation, and `agent_turn_history` is the append-only audit of
every turn (outcome, error code, token usage, duration), read back as windowed aggregates for `@bot usage`.
`agent_ask_turn` is the `/ask` turn ledger: one row per delivered question, moved through a small state machine
by compare-and-set statements, so a turn that has not reached an outcome can be found and run again (2026-10-13).

## Key Files
| File | Description |
|------|-------------|
| `AgentSessionRepository.kt` | Port: `findProviderSessionId(sessionKey): String?`, `saveProviderSessionId(sessionKey, providerSessionId, now: LocalDateTime)` (`now` is the caller's app clock) |
| `AgentSessionRepositoryImpl.kt` | `saveProviderSessionId` is one statement, `JpaAgentSessionRepository.upsertProviderSessionId` — no read before the write. Final class, no `@Transactional` |
| `JpaAgentSessionRepository.kt` | `JpaRepository<AgentSessionSchema, Long>` + derived `findBySessionKey(sessionKey): AgentSessionSchema?`; native `upsertProviderSessionId(sessionKey, providerSessionId, now)` = `INSERT … ON DUPLICATE KEY UPDATE provider_session_id, updated_at` on `uk_agent_session_session_key` (`created_at` and `updated_at` from `:now`) |
| `AgentTurnHistoryRepository.kt` | `data class AgentTurnRecord(sessionKey, requesterId, channel, idempotencyKey: UUID, outcome: AgentTurnOutcome, errorCode?, inputTokens?, outputTokens?, durationMs)`; aggregate rows `AgentTurnOutcomeUsage(outcome, turns, inputTokens, outputTokens, totalDurationMs)` and `RequesterTurnUsage(requesterId, turns, inputTokens, outputTokens)` (all `Long`); port `record(turn)`, `countByOutcomeSince(since)`, `topRequestersSince(since, limit)` |
| `AgentTurnHistoryRepositoryImpl.kt` | Maps `AgentTurnRecord` to `AgentTurnHistorySchema` (UUID → 36-char string) and saves; the two aggregates delegate to the JPQL below (`limit` → `PageRequest.of(0, limit)`) |
| `AgentAskTurnRepository.kt` | `@Repository interface AgentAskTurnRepository : JpaRepository<AgentAskTurnSchema, String>`, used by `:application` directly (no port or `*Impl`, as with `MessageOutboxRepository`). Four native `@Modifying @Transactional` CAS statements, each returning the affected row count and stamping `updated_at` with the caller's `now`: `begin(eventId, attemptCount, now)`, `release(eventId, attemptCount, now)`, `releaseOrphan(eventId, attemptCount, orphanedBefore, now)`, `finish(eventId, attemptCount, status: String, now)`; the native read `findResumable(pendingBefore, orphanedBefore, limit)` (`PENDING` rows with `updated_at < pendingBefore` or `IN_PROGRESS` rows with `updated_at < orphanedBefore`, `ORDER BY updated_at ASC LIMIT`); `data class RecordedAskTurn(turn: AgentAskTurnSchema, storedNow: Boolean)`; and the extension `fun AgentAskTurnRepository.recordDelivered(eventId, messageTs, now): RecordedAskTurn` (insert first → `storedNow = true`; on `DataIntegrityViolationException` the stored row with `storedNow = false`, or the exception again when no row exists; `check`s that no transaction is active) |
| `JpaAgentTurnHistoryRepository.kt` | `JpaRepository<AgentTurnHistorySchema, Long>` + two JPQL constructor-expression queries over `created_at >= :since`: `countByOutcomeSince` (`GROUP BY outcome`) and `topRequestersSince(since, pageable)` (`GROUP BY requesterId ORDER BY COUNT DESC, requesterId ASC`). Token and duration sums are `COALESCE(SUM(...), 0L)`, so a group whose turns all lack token counts reads 0, not null |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `schema/` | `AgentSessionSchema`, `AgentTurnHistorySchema`, `AgentTurnOutcome`, `AgentAskTurnSchema`, `AgentAskTurnStatus` (see `schema/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **`sessionKey` is unique at the DB level, and `saveProviderSessionId` must stay a single upsert that is
  the first statement of `AgentConverseService`'s answer transaction** (2026-10-03). The answer's outbox rows
  share that transaction. On production MariaDB (REPEATABLE READ, `innodb_snapshot_isolation` ON), a
  `findBySessionKey` before the write turned an update by another turn of the same thread into ER_CHECKREAD
  1020 at commit and rolled the answer back; an upsert after any plain read in the transaction fails the same
  way (both reproduced on MariaDB 12.3.3). The old find-then-insert also lost a 1062 race on a brand-new key.
- **The ask-turn ledger is a state machine of single-statement CAS transitions** (2026-10-13). A row is inserted
  `PENDING` with both counters at 0; `event_id` is the outbox `event_id` of the question row and `message_ts` the
  Slack ts of the posted question.

  | statement | from → to | counters | guard besides `event_id` |
  |---|---|---|---|
  | `begin` | `PENDING` → `IN_PROGRESS` | `attempt_count + 1`, `run_count + 1` | `status = 'PENDING'`, `attempt_count` = observed |
  | `release` (busy give-back) | `IN_PROGRESS` → `PENDING` | `run_count − 1`, not below 0 | `status = 'IN_PROGRESS'`, `attempt_count` = observed |
  | `releaseOrphan` | `IN_PROGRESS` → `PENDING` | unchanged | the same, and `updated_at < orphanedBefore` |
  | `finish(status)` | `PENDING` or `IN_PROGRESS` → the bound status | unchanged | `status IN ('PENDING', 'IN_PROGRESS')`, `attempt_count` = observed |

  `attempt_count` is the ownership token: only `begin` raises it and nothing lowers it, so a run that lost the
  row (another run began it since) matches no statement. `run_count` is the give-up budget the application
  compares with its limit: a busy give-back takes its run back, so only runs that were cut off stay counted. Do
  not fold the two into one counter, and do not let `release` or `releaseOrphan` touch `attempt_count`.
  `finish` accepts `PENDING` on purpose: an owner released as an orphan that finishes before anyone began the
  turn again still settles it, and a turn that never ran can be settled (rejected at delivery) or abandoned (the
  sweep). Its `status` is a bound `String`; callers pass `AgentAskTurnStatus.COMPLETED.name` or `ABANDONED.name`.
- **`recordDelivered` must run with no surrounding transaction, and enforces it.** It inserts first
  (`saveAndFlush`; the entity is `Persistable`, so no `SELECT` precedes the `INSERT`) and reads the stored row
  only after a `DataIntegrityViolationException`, so a second delivery of the same question, or a retry after an
  insert whose commit landed but whose acknowledgement was lost, gets the first row with its ts and current
  status. `RecordedAskTurn.storedNow` tells the two apart, and both of those cases report `false`: the caller ends
  a rejected turn with the overload notice only for a row it stored in this call, since a row that already existed
  may be queued on another replica (`application/service/agent/AGENTS.md`). Inside a transaction the refused insert leaves its new instance in the persistence context and the read
  returns that instance instead of the stored row, so the function fails fast with `IllegalStateException`
  (pinned with a real `TransactionTemplate`; the application's caller is the relay dispatch thread, which has
  none). A violation that is not a duplicate (no stored row) is rethrown.
- **Ledger timestamps come from the caller.** `created_at` and `updated_at` have no `@CreationTimestamp` /
  `@UpdateTimestamp`; every insert and statement binds the application clock's `now`, the same clock the sweep's
  cutoffs use. Never write `CURRENT_TIMESTAMP` into these statements.
- **Quoted status names in the native SQL are checked** by `NativeQueryStatusLiteralTest` against
  `AgentAskTurnStatus`; add a constant in both places. The specs run the statements on H2 (`MODE=MariaDB` here,
  plain H2 in `:application` specs). They were also run once by hand on MariaDB 12.3.3 with
  `innodb_snapshot_isolation` on (2026-10-13, local instance, `mariadb` client, server-side prepared statements with
  bound parameters, on a table created by applying `V26` twice): the winning and the losing path of each CAS, the
  `GREATEST` floor at zero, the orphan cutoff one microsecond either side, the bound `LIMIT`, and `findResumable`
  as a range scan on `idx_agent_ask_turn_status_updated_at` (with a filesort). A stale owner's `finish` after
  another run took the turn changed 0 rows in a transaction that had not read yet and failed with error 1020 in
  one that had; either way that transaction rolls back. A duplicate `event_id` insert fails with 1062. Not run on
  MariaDB: anything through JPA and Connector/J (the entity mapping on the `VARCHAR(16)` status column,
  `recordDelivered`'s duplicate-key translation).
- **These `*Impl`s are not `open` and carry no `@Transactional`**, unlike every other lane; each
  `save` runs in Spring Data's own transaction. Follow the `open class` + `@Transactional` shape from
  `repository/cve` if a method ever needs two statements to commit together.
- **`record` is fire-and-forget audit;** a failure here should not fail the user's turn — the caller
  (`AgentConverseService`) decides. Rows are never updated.
- `turnId` in `mcp_tool_call_history` joins `agent_turn_history.idempotency_key`; keep the 36-char string
  form when touching either side. Migrations: `V9` (`agent_session`), `V10` (`agent_turn_history`), `V26`
  (`agent_ask_turn`).
- Beans: `JpaConfiguration.agentSessionRepository` / `agentTurnHistoryRepository`; consumers:
  `:application` `AgentConverseService` (writes) and `AgentUsageReportService` (aggregates). `AgentAskTurnRepository`
  is its own Spring Data bean (picked up by `@EnableJpaRepositories`); its consumer is `:application`
  `AgentAskThreadStarter`.
- `created_at` is `@CreationTimestamp` (JVM clock, system zone), so the `since` a caller passes must come from a
  system-zone clock too; the app's `Clock` bean is `Clock.systemDefaultZone()`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.*'
```
`AgentSessionRepositoryImplTest` (`@DataJpaTest` on H2 `MODE=MariaDB` for the upsert) plus `AgentConverseService`
specs in `:application` that mock the two ports. `AgentTurnHistoryRepositoryImplTest` (same H2 mode) pins the two
aggregates; `record` has no spec here. `AgentAskTurnRepositoryTest` (same H2 mode) pins the winning and every
losing path of each CAS, both counters across a whole life, `recordDelivered` and `findResumable`; the
`:application` `AgentAskThreadStarterTest` drives the same statements through the lease.

### Common Patterns
- Port interface + `*Impl` + `Jpa*Repository` triple; ports expose primitives / records, never entities. The
  ledger is the exception (a Spring Data interface that returns its entity), following `repository/outbox`.
- Named arguments on every repository call.

## Dependencies

### Internal
- `repository/agent/schema`

### External
Spring Data JPA.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
