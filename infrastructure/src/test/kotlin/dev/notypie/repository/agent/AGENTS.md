<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-01 | Updated: 2026-10-13 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/agent

## Purpose
H2 specs for the agent session, turn-history and `/ask` turn-ledger lanes in main `repository/agent/`.

## Key Files
| File | Description |
|------|-------------|
| `AgentSessionRepositoryImplTest.kt` | `@DataJpaTest` on H2 `MODE=MariaDB` (`Replace.NONE`, own in-memory DB) for the native `ON DUPLICATE KEY UPDATE`. Saving a second provider session id for the same thread key replaces it in place: one row, `created_at` from the first call's `now`, `updated_at` from the second. Snapshot isolation (MockK `JpaAgentSessionRepository` inside a `TransactionTemplate` over `SnapshotIsolationTransactionManager`): the save is a single `upsertProviderSessionId` with no `findBySessionKey` — fails with the translated 1020 when the impl reads before writing. |
| `AgentTurnHistoryRepositoryImplTest.kt` | `@DataJpaTest` on its own H2 `MODE=MariaDB` DB. Rows come from `createAgentTurnHistorySchema` and are aged with a JDBC `UPDATE … SET created_at` after `persistAndFlush`, because `@CreationTimestamp` overwrites a constructor value: two `U_ALPHA` `COMPLETED`, one `U_ALPHA` `FAILED` with null tokens, one `U_BETA` `COMPLETED` inside the window (one exactly at `since`), one `U_GAMMA` `BUSY` a minute before it. `countByOutcomeSince` → `COMPLETED` 3 turns / 1,700 in / 170 out / 3,500 ms and `FAILED` 1 / 0 / 0 / 300 ms (COALESCE keeps a null-token group at 0); `topRequestersSince(limit = 5)` → `U_ALPHA` then `U_BETA` with their sums, `limit = 1` → only `U_ALPHA`; a window after every row → both empty. |
| `AgentAskTurnRepositoryTest.kt` | `@DataJpaTest` on its own H2 `MODE=MariaDB` DB over the real `AgentAskTurnRepository`; rows from `createAgentAskTurnSchema`, `entityManager.clear()` before every re-read (a native update leaves the managed instance stale). `begin`: two runs with the same observed attempt → only the first wins, `IN_PROGRESS`, attempt 1, run 1, `updated_at` = the caller's `now`; an `IN_PROGRESS` row is refused on the status guard alone, a `PENDING` row at attempt 1 on the attempt guard alone (`begin(attemptCount = 0)` → 0, row unchanged). `release`: `IN_PROGRESS` attempt 3 run 2 → `PENDING`, attempt 3, run 1; run 0 stays 0; a superseded attempt → 0; `PENDING` and `COMPLETED` rows with a matching attempt → 0, unchanged. `releaseOrphan`: older than the cutoff → `PENDING`, both counters unchanged; touched exactly at the cutoff or another attempt → 0; `PENDING` / `COMPLETED` rows that are old enough → 0. `finish`: from `IN_PROGRESS` → `COMPLETED` with counters unchanged; from `PENDING` at the same attempt (an orphan-released owner) → `COMPLETED`; a stale attempt → 0; a never-run turn → `ABANDONED`; a `COMPLETED` or `ABANDONED` row is not overwritten. A whole life (begin, release, begin, releaseOrphan, finish) gives `(attempt, run)` = `(1,1) (1,0) (2,1) (2,1) (2,1)`. `recordDelivered`, called in the `when` container so that no test transaction is active, with its committed rows deleted afterwards: first call → `storedNow = true`, one `PENDING` row with the ts and the caller's time; a second call with another ts while the turn waits → `storedNow = false`, the stored row untouched (first ts, `PENDING`, attempt 0, first timestamps); a second call with another ts after `begin` → `storedNow = false`, the stored row (first ts, `IN_PROGRESS`, attempt 1, run 1); a 33-character ts → `DataIntegrityViolationException` rethrown, nothing stored; called inside a `TransactionTemplate` → `IllegalStateException`, nothing stored. `findResumable`: of six rows only the orphaned run and the waiting turn come back, least recently touched first, with their status and attempt count, and `limit = 1` returns only the first |

## For AI Agents

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.agent.*'
```
Every case seeds its rows inside the `then` leaf, so `@DataJpaTest` rolls them back and the H2 keeps no rows between cases.
The `recordDelivered` cases of `AgentAskTurnRepositoryTest` are the exception: the function refuses an active
transaction, so they run in the `when` container, commit, and delete their rows themselves. H2 does not show how
MariaDB executes these statements.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
