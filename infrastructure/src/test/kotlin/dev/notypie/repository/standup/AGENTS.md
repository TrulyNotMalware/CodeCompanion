<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-01 | Updated: 2026-10-01 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/standup

## Purpose
H2 specs for the standup persistence lane in main `repository/standup/`.

## Key Files
| File | Description |
|------|-------------|
| `StandupDispatchSweepTest.kt` | `@DataJpaTest` on `StandupRepositoryImpl`: a dispatch claimed with `now = 2020-01-01T00:00Z` (far from H2's own clock) is not reset by a cutoff a minute before the claim and is reset by one a minute after, so `updated_at` comes from the bound clock, not `CURRENT_TIMESTAMP` |
| `JpaStandupSessionRepositoryTest.kt` | `@DataJpaTest`. A session with three dispatches and two answers, saved through `StandupSession.toSchema()`, flushed and cleared, then loaded by `findBySessionUid` (which `LEFT JOIN FETCH`es both collections): three dispatches, two answers, and two answers in `toStandupSessionDto()`. With `answers` mapped as a List the same load returned six answers |

## For AI Agents

### Working In This Directory
- Flush and clear the `TestEntityManager` before reading back; otherwise the persistence context returns the
  saved instance and the fetch-join query's repeated rows never show.
- Writes stay inside the leaf so the test transaction rolls them back.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.standup.*'
```
