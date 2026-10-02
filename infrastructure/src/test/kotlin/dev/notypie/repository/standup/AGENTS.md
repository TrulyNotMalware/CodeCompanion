<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-09-30 | Updated: 2026-10-01 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/standup

## Purpose
Specs for the standup persistence lane in main `repository/standup/`. The lane's first H2 coverage: a
`@DataJpaTest` that drives `StandupRepositoryImpl` over the real Spring Data repositories and Hibernate, so
SQL ordering (IDENTITY inserts vs. orphan deletes) and the native CAS statements run as in production.

## Key Files
| File | Description |
|------|-------------|
| `StandupRepositoryImplJpaTest.kt` | `@DataJpaTest(properties = spring.datasource.url = jdbc:h2:mem:standup_mariadb;MODE=MariaDB…)` + `@AutoConfigureTestDatabase(replace = NONE)` + Kotest `SpringExtension` — MariaDB mode because `upsertAnswer` is `INSERT … ON DUPLICATE KEY UPDATE`, which H2's default mode rejects; builds `StandupRepositoryImpl` by hand and wraps each call in a `TransactionTemplate` (the impl is not a Spring proxy here, so its `@Transactional` would not apply). A session with three dispatches and two answers reads back with each answer once through `findSession(sessionUid)`, `findSession(routineUid, sessionDate)` and `findCollectingSessionsPastCutoff` (review G1/G12; the old one-dispatch fixture could not see the bag duplication, 3 × 2 came back as 6). `recordAnswer` resubmitted by the same member in a later transaction replaces the row — one answer, the second responses and `submittedAt` — instead of throwing on `uk_standup_answer_session_user` (review T9; fails on the old remove + add). Two first submissions by one member where the second transaction read the session before the first committed → both `RECORDED`, one row holding the later answer and its `submittedAt` (G4; the previous find-then-insert failed here on `uk_standup_answer_session_user`). `recordAnswer` on a `SUMMARIZED` session, or at/after `cutoffAt` on a `COLLECTING` one, returns `SESSION_CLOSED` and writes nothing; an unknown uid returns `SESSION_NOT_FOUND` (T19). Race (G5 / Codex R5), two threads and two real transactions holding the lock for 300 ms: the summary locks first and commits `SUMMARIZED` → the concurrent `recordAnswer` waits and returns `SESSION_CLOSED` with no row written; the answer locks first → `findSessionForSummary` waits and returns the answer. Both fail when either side reads without the lock (checked by removing it). `markDispatchSkipped` turns a `PENDING` row `SKIPPED` once (second call `false`, first reason kept) and drops it from `findPendingDispatchesBefore`. `afterSpec` deletes the sessions it wrote |

## For AI Agents

### Working In This Directory
- **`@DataJpaTest` here does not roll back** (same as `../cve/`): Kotest container scopes run outside the
  test transaction, so rows commit. This spec has its own MariaDB-mode H2 (`standup_mariadb`), not the H2 the
  other `@DataJpaTest` specs share; still give each block its own session and clean up in `afterSpec`.
- Keep the MariaDB mode: dropping it turns `upsertAnswer` into a syntax error. Race blocks use two threads, each
  with its own `TransactionTemplate` transaction, and a 300 ms hold — well under H2's lock timeout.
- Build sessions with the domain fixtures (`createStandupSession`, `createSessionDispatch`) and persist them
  through `repository.createSession`, so the `toSchema` mapping is exercised too.
- Keep one transaction per production call when reproducing ordering bugs — two calls in one transaction
  hide what a real resubmission does.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.standup.*'
```

## Dependencies

### Internal
- `repository/standup/` — `StandupRepositoryImpl`, `JpaRoutineRepository`, `JpaStandupSessionRepository`,
  `JpaSessionDispatchRepository`
- `domain` testFixtures `standup/StandupTestFixtures.kt`

### External
Spring Boot `@DataJpaTest`, H2, Kotest + `kotest-extensions-spring`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
