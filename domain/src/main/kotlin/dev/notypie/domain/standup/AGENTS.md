<!-- Parent: ../../../../../../../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# domain/standup

## Purpose
The standup aggregate: recurring routines, the per-day sessions they produce, the per-member DM
dispatches, and the collected answers. Pure Kotlin; JPA schemas mirroring these entities live in
`infrastructure/repository/standup/schema/`.

## Key Files
| File | Description |
|------|-------------|
| `entity/Routine.kt` | Recurring standup config bound to one `commandChannel`: questions, `triggerLocalTime`, `cutoffOffset`, active weekdays, `routineTimezone`, separate `summaryChannel` |
| `entity/RoutineMember.kt` | A routine participant with their own `userTimezone` (drives per-member DM time) |
| `entity/StandupSession.kt` | One day's bucket, unique on `(routineUid, sessionDate)`; holds `cutoffAt`, dispatches, answers, `summaryMessageTs` |
| `entity/SessionDispatch.kt` | Per-member DM record with `dmTriggerAt`; keyed by Slack `userId` |
| `entity/StandupAnswer.kt` | A member's submitted answers for a session |
| `entity/enums/SessionStatus.kt` | `COLLECTING` → `SUMMARIZED` (and terminal states) |
| `entity/enums/DispatchStatus.kt` | Lifecycle of one member's DM dispatch |
| `dto/RoutineDto.kt` | Routine read-model crossing the repository boundary |
| `dto/StandupSessionDto.kt` | Session read-model used by the scheduler and summary service |

## For AI Agents

### Working In This Directory
Three modeling decisions are load-bearing; changing them ripples through the schedulers in
`application/service/standup/`:
1. **Channel × day = 1 session.** `(routineUid, sessionDate)` is unique. `commandChannel` identifies
   the standup.
2. **Per-user timezone.** `Routine.routineTimezone` only decides *which calendar date* a session is
   for; each member's DM fires at a time derived from their own `RoutineMember.userTimezone`. A single
   session can therefore span a wide UTC window.
3. **Configurable summary channel.** `summaryChannel` may differ from `commandChannel` so summaries can
   land in an archive channel.

Other rules:
- Invariants belong in `init { validate { ... } }` so JPA loads cannot resurrect broken rows — e.g. a
  `SUMMARIZED` session must carry a non-blank `summaryMessageTs`. That field is also the idempotency
  guard: a re-run (scheduler retried after restart) detects an already-posted summary and short-circuits.
- `addDispatch` collapses duplicates on Slack `userId` so retried session opens do not double-count members.
- This package must not import `dev.notypie.domain.command` — enforced by the architecture guard test.

### Testing Requirements
```bash
./gradlew :domain:test --tests '*Standup*' --tests '*Routine*'
```
Specs: `domain/src/test/kotlin/dev/notypie/domain/standup/entity/` (`RoutineTest`, `StandupSessionTest`).
Fixtures: `domain/src/testFixtures/kotlin/dev/notypie/domain/standup/StandupTestFixtures.kt`.
Any new timezone or cutoff behaviour needs a spec that crosses a day boundary — that is where these
models historically break.

### Common Patterns
- `LocalTime` + `ZoneId` + `DayOfWeek` for the recurring config; `Instant` for absolute fire/cutoff times.
- Private mutable collections with snapshot accessors, same as `domain/meet`.
- `UUID` business keys (`routineUid`, `sessionUid`) generated in the domain.

## Dependencies

### Internal
- `domain/common/` — `validate { }` DSL

### External
`java.time`, `java.util` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
