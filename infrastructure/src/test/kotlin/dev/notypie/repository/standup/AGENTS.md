<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-01 | Updated: 2026-10-02 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/standup

## Purpose
H2 specs for the standup persistence lane in main `repository/standup/`; `StandupRepositoryImplTest` runs H2 in
`MODE=MariaDB` on its own in-memory database because the answer upsert is MariaDB syntax.

## Key Files
| File | Description |
|------|-------------|
| `StandupRepositoryImplTest.kt` | `@DataJpaTest(spring.datasource.url = …;MODE=MariaDB)` + `@AutoConfigureTestDatabase(replace = NONE)`, real `TransactionTemplate` transactions committed outside the test transaction. `recordAnswer`: a resubmission replaces the row; two first submissions by one member, the second deciding from an older view, leave one row with the later answer and no unique violation; a `SUMMARIZED` session, an answer at or after `cutoffAt`, and an unknown uid → `SESSION_CLOSED` / `SESSION_NOT_FOUND` with no row; a transaction that already holds the session when another commits `SUMMARIZED` → `SESSION_CLOSED` (the managed instance's stale status is not trusted). Lock handshake on two threads: the summary locks first and commits → the answer waits and is `SESSION_CLOSED`; the answer locks first → `findSessionForSummary` waits and includes it. `recordDispatchFailure` on a `PENDING` row → `true`, the cause is stored and the row stays queued with it; on a claimed row → `false`, untouched. `markDispatchSkipped` twice → `true` then `false`, the row is `FAILED` with `skipped: closed` and leaves the pending queue |
| `StandupDispatchSweepTest.kt` | `@DataJpaTest` on `StandupRepositoryImpl`: a dispatch claimed with `now = 2020-01-01T00:00Z` (far from H2's own clock) is not reset by a cutoff a minute before the claim and is reset by one a minute after, so `updated_at` comes from the bound clock, not `CURRENT_TIMESTAMP` |
| `JpaStandupSessionRepositoryTest.kt` | `@DataJpaTest`. A session with three dispatches and two answers, saved through `StandupSession.toSchema()`, flushed and cleared, then loaded by `findBySessionUid` (which `LEFT JOIN FETCH`es both collections): three dispatches, two answers, and two answers in `toStandupSessionDto()`. With `answers` mapped as a List the same load returned six answers |

## For AI Agents

### Working In This Directory
- Flush and clear the `TestEntityManager` before reading back; otherwise the persistence context returns the
  saved instance and the fetch-join query's repeated rows never show.
- Writes stay inside the leaf so the test transaction rolls them back, except in `StandupRepositoryImplTest`,
  whose rows commit through `inTx` (each session gets a random `routineUid`, so blocks do not collide) because
  the lock cases need two real transactions.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.standup.*'
```
