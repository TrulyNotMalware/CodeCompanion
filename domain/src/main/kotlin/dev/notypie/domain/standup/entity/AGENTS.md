<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/standup/entity

## Purpose
The standup aggregates: `Routine` (recurring configuration plus members), `StandupSession` (one day's
run) with its per-member `SessionDispatch` records and `StandupAnswer` submissions. Every class validates
itself in `init`, and the same constructors are used when `infrastructure` rehydrates rows.

## Key Files
| File | Description |
|------|-------------|
| `Routine.kt` | `Routine(name, creatorId, commandChannel, summaryChannel, questions, triggerLocalTime, cutoffOffset, weekdays, routineTimezone, isActive = true, routineUid = random)`; limits `MAX_NAME_LENGTH = 60`, `MIN_QUESTIONS = 1`, `MAX_QUESTIONS = 8`, `MAX_QUESTION_LENGTH = 200`, `MAX_MEMBERS = 30`; defaults `DEFAULT_WEEKDAYS` (Mon–Fri), `DEFAULT_CUTOFF_OFFSET` (1h); `addMember` replaces by `userId`; `memberSnapshot()` / `memberIdSnapshot()` |
| `RoutineMember.kt` | `RoutineMember(userId, userTimezone: ZoneId)`; non-blank `userId` |
| `StandupSession.kt` | `StandupSession(routineUid, sessionDate, cutoffAt, status = COLLECTING, summaryMessageTs?, sessionUid = random)`; `SUMMARIZED` requires non-blank `summaryMessageTs`; `addDispatch` / `addAnswer` replace by `userId`; `dispatchSnapshot()` / `answerSnapshot()` |
| `SessionDispatch.kt` | `SessionDispatch(userId, dmTriggerAt: Instant, dmSentAt?, dmStatus = PENDING, failureReason?)`; `SENT` requires `dmSentAt`, `FAILED` requires non-blank `failureReason` |
| `StandupAnswer.kt` | `StandupAnswer(userId, responses, submittedAt)`; at least one response; order mirrors `Routine.questions` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `enums/` | `SessionStatus` (`COLLECTING → SUMMARIZED | SKIPPED`) and `DispatchStatus` (`PENDING → SENDING → SENT | FAILED`) (see `enums/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- Constructed in two places: the application layer (`StandupRoutineSetupService` builds `Routine` +
  `RoutineMember`; `StandupSchedulingService` builds `StandupSession` + `SessionDispatch`) and the JPA
  mappers `RoutineSchema.toDomainEntity()` / `StandupSessionSchema.toDomainEntity()`. A new `init` rule
  therefore runs on every read — if existing rows violate it, reads fail. Migrate data before tightening.
- `Routine` rejects a question containing `\n` because persistence joins questions with `\n` and the
  modal renders each as a label. Keep that rule in sync with `infrastructure/repository/standup/schema/`.
- `status` fields are constructor `val`s with no mutators. Transitions happen as compare-and-set SQL in
  `JpaSessionDispatchRepository` (`claimDispatch`, `markSent`, `markFailed`, `resetStuckSending`) and
  `JpaStandupSessionRepository` (`markSummarized`, `WHERE status = 'COLLECTING'`). The `init` invariants
  here (`SENT ⇒ dmSentAt`, `FAILED ⇒ failureReason`, `SUMMARIZED ⇒ summaryMessageTs`) are the load-time
  contract for what those queries write.
- Replace-by-`userId` in `addMember`, `addDispatch`, `addAnswer` is intentional: retried session opens and
  resubmitted modals must not double-count. Persistence relies on one row per user per session.
- `StandupAnswer` deliberately does not check `responses.size == questions.size`; that belongs at the
  modal-submission boundary (`command/entity/context/form/StandupAnswerSubmissionContext.kt`).
- `SessionStatus.SKIPPED` has no writer anywhere in the repo today; the entity only guards `SUMMARIZED`.
- Must not import `dev.notypie.domain.command` (guard-enforced one-way edge).

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.standup.entity.*'
```
Specs: `domain/src/test/kotlin/dev/notypie/domain/standup/entity/RoutineTest.kt` and
`StandupSessionTest.kt` (the latter also covers `SessionDispatch` and `StandupAnswer` invariants).
Fixtures: `createRoutine`, `createRoutineMember`, `createStandupSession`, `createSessionDispatch`,
`createStandupAnswer` in `domain/src/testFixtures/kotlin/dev/notypie/domain/standup/StandupTestFixtures.kt`
— they use fixed instants, keep new cases deterministic the same way. Any timezone or cutoff change needs
a case that crosses a day boundary.

### Common Patterns
- `validate(className = this.javaClass.simpleName) { ... }` in `init`; limits as `companion object`
  constants referenced by name from tests.
- Constructor collections are defensively copied (`questions.toList()`, `weekdays.toSet()`); internal
  collections are `private` with `*Snapshot()` accessors — never expose the mutable reference.
- `LocalTime` / `ZoneId` / `DayOfWeek` / `Duration` for recurring config, `Instant` for absolute times,
  `LocalDate` for the session's calendar day, `UUID` business keys generated in the constructor default.

## Dependencies

### Internal
- `domain/common/` — `validate { }` DSL
- `enums/` — `DispatchStatus`, `SessionStatus`

### External
`java.time`, `java.util.UUID` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
