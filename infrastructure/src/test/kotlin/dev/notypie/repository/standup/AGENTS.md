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
| `StandupRepositoryImplJpaTest.kt` | `@DataJpaTest` + Kotest `SpringExtension`; builds `StandupRepositoryImpl` by hand and wraps each call in a `TransactionTemplate` (the impl is not a Spring proxy here, so its `@Transactional` would not apply). A session with three dispatches and two answers reads back with each answer once through `findSession(sessionUid)`, `findSession(routineUid, sessionDate)` and `findCollectingSessionsPastCutoff` (review G1/G12; the old one-dispatch fixture could not see the bag duplication, 3 × 2 came back as 6). `recordAnswer` resubmitted by the same member in a later transaction replaces the row — one answer, the second responses and `submittedAt` — instead of throwing on `uk_standup_answer_session_user` (review T9; fails on the old remove + add). `recordAnswer` on a `SUMMARIZED` session, or at/after `cutoffAt` on a `COLLECTING` one, returns `SESSION_CLOSED` and writes nothing; an unknown uid returns `SESSION_NOT_FOUND` (T19). `markDispatchSkipped` turns a `PENDING` row `SKIPPED` once (second call `false`, first reason kept) and drops it from `findPendingDispatchesBefore`. `afterSpec` deletes the sessions it wrote |

## For AI Agents

### Working In This Directory
- **`@DataJpaTest` here does not roll back** (same as `../cve/`): Kotest container scopes run outside the
  test transaction, so rows commit to the H2 shared by every `@DataJpaTest` spec. Give each block its own
  `routineUid` / session and clean up in `afterSpec`.
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
