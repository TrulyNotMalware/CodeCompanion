<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-20 -->

# infrastructure/repository/agent

## Purpose
Persistence for the AI-agent lane: `agent_session` maps a Slack conversation key to the sidecar's own
session id so a thread resumes as one conversation, and `agent_turn_history` is the append-only audit of
every turn (outcome, error code, token usage, duration), read back as windowed aggregates for `@bot usage`.
`agent_ask_turn` is the `/ask` turn ledger: one row per delivered question, moved through a small state machine
by compare-and-set statements, so a turn that has not reached an outcome can be found and run again (2026-10-13).
`agent_turn_history` and the ledger's finished rows are purged past their retention by `:application`
`AgentRetentionScheduler` (2026-10-15); `agent_session` is never purged.

## Key Files
| File | Description |
|------|-------------|
| `AgentSessionRepository.kt` | Port: `findProviderSessionId(sessionKey): String?`, `saveProviderSessionId(sessionKey, providerSessionId, now: LocalDateTime)` (`now` is the caller's app clock) |
| `AgentSessionRepositoryImpl.kt` | `saveProviderSessionId` is one statement, `JpaAgentSessionRepository.upsertProviderSessionId` — no read before the write. Final class, no `@Transactional` |
| `JpaAgentSessionRepository.kt` | `JpaRepository<AgentSessionSchema, Long>` + derived `findBySessionKey(sessionKey): AgentSessionSchema?`; native `upsertProviderSessionId(sessionKey, providerSessionId, now)` = `INSERT … ON DUPLICATE KEY UPDATE provider_session_id, updated_at` on `uk_agent_session_session_key` (`created_at` and `updated_at` from `:now`) |
| `AgentTurnHistoryRepository.kt` | `data class AgentTurnRecord(sessionKey, requesterId, channel, idempotencyKey: UUID, outcome: AgentTurnOutcome, errorCode?, inputTokens?, outputTokens?, durationMs)`; aggregate rows `AgentTurnOutcomeUsage(outcome, turns, inputTokens, outputTokens, totalDurationMs)` and `RequesterTurnUsage(requesterId, turns, inputTokens, outputTokens)` (all `Long`); port `record(turn)`, `countByOutcomeSince(since)`, `topRequestersSince(since, limit)`, `deleteOlderThan(olderThan, limit): Int` (the retention purge, 2026-10-15) |
| `AgentTurnHistoryRepositoryImpl.kt` | Maps `AgentTurnRecord` to `AgentTurnHistorySchema` (UUID → 36-char string) and saves; the two aggregates delegate to the JPQL below (`limit` → `PageRequest.of(0, limit)`), `deleteOlderThan` to the native delete |
| `AgentAskTurnRepository.kt` | `@Repository interface AgentAskTurnRepository : JpaRepository<AgentAskTurnSchema, String>`, used by `:application` directly (no port or `*Impl`, as with `MessageOutboxRepository`). Four native `@Modifying @Transactional` CAS statements, each returning the affected row count and stamping `updated_at` with the caller's `now`: `begin(eventId, attemptCount, now)`, `release(eventId, attemptCount, now)`, `releaseOrphan(eventId, attemptCount, orphanedBefore, now)`, `finish(eventId, attemptCount, status: String, now)`; the native read `findResumable(pendingBefore, orphanedBefore, limit)` (`PENDING` rows with `updated_at < pendingBefore` or `IN_PROGRESS` rows with `updated_at < orphanedBefore`, `ORDER BY updated_at ASC LIMIT`); the native `@Modifying @Transactional` `deleteFinishedOlderThan(olderThan, limit): Int` (`DELETE … WHERE status IN ('COMPLETED', 'ABANDONED') AND updated_at < :olderThan LIMIT :limit`, the retention purge; `PENDING` / `IN_PROGRESS` rows are never deleted, however old, 2026-10-15); `data class RecordedAskTurn(turn: AgentAskTurnSchema, storedNow: Boolean)`; and the extension `fun AgentAskTurnRepository.recordDelivered(eventId, messageTs, now): RecordedAskTurn` (insert first → `storedNow = true`; on `DataIntegrityViolationException` the stored row with `storedNow = false`, or the exception again when no row exists; `check`s that no transaction is active) |
| `JpaAgentTurnHistoryRepository.kt` | `JpaRepository<AgentTurnHistorySchema, Long>` + two JPQL constructor-expression queries over `created_at >= :since`: `countByOutcomeSince` (`GROUP BY outcome`) and `topRequestersSince(since, pageable)` (`GROUP BY requesterId ORDER BY COUNT DESC, requesterId ASC`). Token and duration sums are `COALESCE(SUM(...), 0L)`, so a group whose turns all lack token counts reads 0, not null. Native `@Modifying @Transactional` `deleteOlderThan(olderThan, limit): Int` = `DELETE … WHERE created_at < :olderThan LIMIT :limit` on `idx_agent_turn_history_created_at` (2026-10-15) |

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
  MariaDB through JPA and Connector/J: the entity mapping on the script's `VARCHAR(16)` status column and
  `recordDelivered`'s duplicate-key translation (the retention purge's native deletes did run through that path, on a
  Hibernate-generated table, 2026-10-20 — below).
- **These `*Impl`s are not `open` and carry no `@Transactional`**, unlike every other lane; each
  `save` runs in Spring Data's own transaction. Follow the `open class` + `@Transactional` shape from
  `repository/cve` if a method ever needs two statements to commit together.
- **`record` is fire-and-forget audit;** a failure here should not fail the user's turn — the caller
  (`AgentConverseService`) decides. Rows are never updated; they are deleted only by the retention purge.
- **Retention deletes are bounded, native and status-guarded** (2026-10-15). JPQL bulk deletes cannot take a
  `LIMIT`, so each purge statement is native `DELETE … LIMIT :limit` like the outbox purge, one statement per
  transaction, and the caller loops until a batch comes back short. The ledger purge must keep its `status IN
  ('COMPLETED', 'ABANDONED')` guard: deleting a `PENDING` / `IN_PROGRESS` row makes its owner's next CAS change 0
  rows (`AgentTurnLeaseLostException`, the answer rolls back) and drops the turn from the sweep. The status list is a
  literal, so `AgentAskTurnRepositoryTest` classifies every `AgentAskTurnStatus` constant in a `when` without `else`
  and checks that exactly the finished ones are deleted: a new constant does not compile until it is classified.
- **Run each purge statement outside any surrounding transaction** (2026-10-19). Each `DELETE … LIMIT` must be the
  first and only statement of its own transaction (the repository method's `@Transactional`). Wrapping several batches
  or the whole purge in one transaction holds every batch's locks to the end. Reading before the delete, on MariaDB
  with `innodb_snapshot_isolation`, makes the `DELETE` itself fail with ER_CHECKREAD 1020 (at the statement, not at
  commit; 0 rows) when it reaches a target row that another transaction updated and committed after that read
  (measured 2026-10-20). In the tested overlap — a read-before-delete transaction on `agent_turn_history` while another
  session purged the same range — there was no 1020: the rows the other side deleted were skipped. Finished ledger rows
  and audit rows are never updated, so no code path today reaches the update case; keep the rule anyway.
- **Measured on MariaDB 12.3.3 (2026-10-20, local; scripts and output in the maintainer's
  `.omc/artifacts/mariadb-verify-2026-10-20/`):**
  1. `EXPLAIN DELETE …` for the three purge statements is a range on `idx_agent_ask_turn_status_updated_at` (two status
     ranges) and on each history table's `created_at` index, with few, no and many target rows among 20,000; a
     rolled-back `ANALYZE DELETE` examined 10 / 0 / 1000 rows (it stops at `LIMIT`; no full scan when nothing matches).
     Should a plan ever stop being a range, a smaller `batch-size` does not help: `LIMIT` bounds the rows deleted, not
     the rows scanned or locked under REPEATABLE READ — fix the index or the statement.
  2. Two sessions purging the same range of the ledger (`mariadb` client, autocommit `DELETE … LIMIT 1000` loops): the
     second waits on the first's row locks for as long as the first batch stays open, then continues; the batches'
     `ROW_COUNT()` values (each at most 1000) of both sessions add up to the eligible rows exactly, there was no
     deadlock, and the ineligible rows stay — old `PENDING` / `IN_PROGRESS` rows among them, so the status guard holds
     on MariaDB. The scheduler's startup jitter only lowers the chance of that overlap.
  3. The successive-batch contract the scheduler relies on — each call deletes at most `limit` rows and returns the true
     count, so the loop drains a backlog and stops on the first short batch — on the real path (scheduler → Spring Data
     native query → Hibernate → MariaDB Connector/J), with `batch-size` 3 and 7 / 6 / 8 eligible rows: the ROW binlog
     shows per-transaction deletes of `[3, 3, 1]`, `[3, 3]` and `[3, 3, 2]` (never more than `limit`), the tick's logged
     totals 7 / 6 / 8 equal the rows deleted, and the backlog drained. The per-call return values were not logged, so a
     wrong count that still sums correctly (`[3, 4, 0]` for `[3, 3, 1]`) is not ruled out on this path. The old
     `PENDING` / `IN_PROGRESS` rows seeded for that run were turned `ABANDONED` by the resume sweep before the tick, so
     this run did not exercise the status guard (check 2 did). That app ran on the tables Hibernate generated locally
     (`status` an `enum` column); the application on the `V26` script's table (`VARCHAR(16)`) is still unverified
     (`db/migration/AGENTS.md`). The H2 specs still cannot prove the contract (next bullet), and
     `AgentRetentionSchedulerTest` feeds the counts from MockK.

  A `DELETE … LIMIT` without `ORDER BY` is flagged unsafe for statement-based binlog; the repository's MariaDB configs set
  `binlog_format=ROW` (`resources/cdc/docker-compose/mariadb/my.cnf`, `cdc/k8s/yamls/mariadb/mariadb-config.yaml`).
- **H2 miscounts these deletes after the first call** (2026-10-17, observed in `AgentAskTurnRepositoryTest` with exact
  assertions, H2 2.4.240 `MODE=MariaDB`, Hibernate-generated table). The statement as Hibernate sent it —
  `DELETE FROM agent_ask_turn WHERE status IN ('COMPLETED', 'ABANDONED') AND updated_at < ? LIMIT ?` with a `TIMESTAMP`
  cutoff and `INTEGER` 2 bound — gave, for three `COMPLETED` rows past the cutoff, counts/rows left `[2, 1, 0, 0]`
  (expected `[2, 1, 1, 0]`: the second call deleted the last row and reported 0), and for three `COMPLETED` plus three
  `ABANDONED` rows `[(2, 4), (2, 1), (0, 0), (0, 0)]` (expected `[(2, 4), (2, 2), (2, 0), (0, 0)]`: the second call deleted
  three rows under `LIMIT 2` and reported 2). Same result inside the test transaction, with every call committed, and
  on a `VARCHAR` copy of the table through the same Hibernate path; the trigger is not identified (a direct JDBC run of
  the statement on the same jar counted correctly in review). So the ledger specs assert the first call exactly and, for
  later calls, only what holds even under that miscount: each reported count is within `0..limit`, the eligible rows left
  never increase and reach zero, and ineligible rows stay. A later call deleting more than `limit`, or returning a wrong
  count, would still pass. The same Hibernate path counted correctly on MariaDB (check 3 above); the cause of the H2
  miscount is still unidentified.
- `turnId` in `mcp_tool_call_history` joins `agent_turn_history.idempotency_key`; keep the 36-char string
  form when touching either side. Migrations: `V9` (`agent_session`), `V10` (`agent_turn_history`), `V26`
  (`agent_ask_turn`).
- Beans: `JpaConfiguration.agentSessionRepository` / `agentTurnHistoryRepository`; consumers:
  `:application` `AgentConverseService` (writes), `AgentUsageReportService` (aggregates) and `AgentRetentionScheduler`
  (purge). `AgentAskTurnRepository` is its own Spring Data bean (picked up by `@EnableJpaRepositories`); its consumers
  are `:application` `AgentAskThreadStarter` and `AgentRetentionScheduler`.
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
`:application` `AgentAskThreadStarterTest` drives the same statements through the lease. The retention deletes are
pinned on the same H2 mode: the ledger purge deletes only finished rows strictly older than the cutoff (and exactly the
statuses a `when` over `AgentAskTurnStatus` classifies as finished), and its first call honours the limit with one or
both finished statuses; each history purge deletes only rows created strictly before the cutoff, at most `limit` per
call.

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
