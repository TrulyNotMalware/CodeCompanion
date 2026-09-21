<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# domain/standup/entity (test)

## Purpose
Invariants of the standup aggregates: `Routine` (the schedule definition and its members) and
`StandupSession` (one day's run, with `SessionDispatch` DM bookkeeping and `StandupAnswer` submissions).

## Key Files
| File | Description |
|------|-------------|
| `RoutineTest.kt` | `BehaviorSpec` over `Routine` / `RoutineMember`. Valid construction preserves question order and the weekday set, `isActive` defaults to `true`, `memberSnapshot()` starts empty. Rejections (`ValidationExceptionWithName`): blank `name`, empty `questions`, a blank question, a question containing `\n` (would break the persistence delimiter), a question > `MAX_QUESTION_LENGTH`, more than `MAX_QUESTIONS`, empty `weekdays`, `cutoffOffset` ≤ 0. `addMember`: same `userId` twice keeps only the latest (timezone overwritten), one past `MAX_MEMBERS` is rejected. Uses `createRoutine`, `createRoutineMember` |
| `StandupSessionTest.kt` | `BehaviorSpec` over `StandupSession`, `SessionDispatch`, `StandupAnswer`. `SUMMARIZED` status requires a `summaryMessageTs`; `addDispatch` and `addAnswer` replace an existing row for the same `userId` (latest wins, no duplicates); `SessionDispatch` with `SENT` needs `dmSentAt`, with `FAILED` needs `failureReason`; `StandupAnswer` needs at least one response. Uses `createStandupSession`, `createSessionDispatch`, `createStandupAnswer` |

## For AI Agents

### Working In This Directory
- Limits come from `Routine.MAX_*` constants and `Routine.DEFAULT_*` defaults; do not restate them as
  literals.
- The fixtures use fixed instants (`2026-05-01T0x:00:00Z`) rather than `now()`, so these specs are
  fully deterministic — keep new cases that way.
- Replace-by-`userId` semantics for members, dispatches, and answers is an intentional contract that
  the persistence layer relies on (one row per user per session); a spec here is the first thing to
  turn red if that changes.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.standup.entity.*'
```

### Common Patterns
- One off-spec field per `when`, asserted with `shouldThrow<ValidationExceptionWithName>`.
- Snapshot accessors (`memberSnapshot()`, `dispatchSnapshot()`, `answerSnapshot()`) with `.single()`
  to assert de-duplication.

## Dependencies

### Internal
- `dev.notypie.domain.standup.entity.*` and `entity.enums.{DispatchStatus, SessionStatus}`;
  `dev.notypie.domain.common.error.ValidationExceptionWithName`.
- `testFixtures` — `standup/StandupTestFixtures.kt`.

### External
- Kotest (`BehaviorSpec`, `shouldThrow`, `shouldBe`), `java.time`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
