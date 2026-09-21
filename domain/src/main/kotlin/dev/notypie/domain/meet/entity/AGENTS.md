<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/meet/entity

## Purpose
The `Meeting` aggregate root with its `Member` value, the `MeetingReminder` row contract for pre-meeting
reminder batches, and the `RejectReason` enum a participant declines with. All invariants are enforced
in `init { validate { ... } }` so the same rules apply whether an instance is built from a Slack modal
or rehydrated from JPA.

## Key Files
| File | Description |
|------|-------------|
| `Meeting.kt` | `Meeting(title, publisher, members: Set<String>, reason, startAt, endAt = startAt + 1h, isCanceled = false, meetingUid = random)`; limits `MAX_PARTICIPANTS = 20`, `MAX_TITLE_LENGTH = 20`, `MAX_REASON_LENGTH = 200`; `startAt` after `now()`, `endAt` after `startAt`; `host` built from `publisher`; `addParticipant(Member)` re-checks the cap; `memberSnapshot()` / `memberIdSnapshot()`. `Member(userId, isGuest = false, isHost = false)` requires a non-blank `userId` |
| `MeetingReminder.kt` | `MeetingReminder(offsetMinutes, scheduledAt: Instant, sentAt?, status = PENDING, failureReason?)`; `offsetMinutes > 0`; `SENT` requires `sentAt`, `FAILED` requires a non-blank `failureReason`. `scheduledAt` is pre-computed as `startAt - offsetMinutes` in UTC |
| `RejectReason.kt` | `enum RejectReason(showMessage)`: `ATTENDING` (the "not absent" value) plus `SCHEDULE_CONFLICT`, `UNEXPECTED_EMERGENCY`, `HEALTH_ISSUE`, `PRIOR_COMMITMENT`, `REQUEST_DELAY`, `VACATION`, `PERSONAL_REASON`, `OTHER` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `enums/` | `MeetingReminderStatus` — `PENDING → SENDING → SENT | FAILED` (see `enums/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- `host` is **not** in `participants`: `memberSnapshot()` / `memberIdSnapshot()` return only the
  `members` passed in (each as a non-host `Member`), so the publisher appears there only if the caller
  also listed them. Callers that need "everyone" must add `host` themselves.
- `Meeting` is rehydrated in production via `MeetingSchema.toDomainEntity()`
  (`infrastructure/repository/meeting/MeetingRepositoryImpl.kt`): once after `createNewMeeting` and once
  in the add-participant path, where it runs inside `runCatching` as the capacity check. Because the
  constructor requires `startAt > now()`, rehydrating a meeting whose start time has passed throws, and
  that path reports `OVER_CAPACITY` regardless of the participant count. Keep that coupling in mind
  before adding constructor rules or reusing `toDomainEntity()` on a read path.
- There is no `cancel()` / `reschedule()` mutator: `isCanceled` is a constructor flag and both operations
  are repository updates whose host-only check lives in the SQL `WHERE` clause. Do not add a domain guard
  for it (see `../AGENTS.md`).
- `MeetingReminder` has no production constructor call and no domain spec: the scheduler works on
  `MeetingReminderDto`, and `JpaMeetingReminderRepository` drives the state machine with SQL. The `init`
  rules document the row contract those queries must satisfy; if you start constructing it, add a spec.
- `RejectReason` is persisted by name (`@Enumerated(EnumType.STRING)` in `MeetingSchema`) and rendered
  through `showMessage` by `infrastructure/templates/ModalTemplateBuilder.kt`; `absentReasonDetail` is
  captured only for `OTHER`. Renaming a constant is a data migration.
- Must not import `dev.notypie.domain.command` (guard-enforced one-way edge).

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.meet.entity.MeetingTest'
```
Spec: `domain/src/test/kotlin/dev/notypie/domain/meet/entity/MeetingTest.kt` — one off-spec field per
`when`, asserted with `shouldThrow<ValidationExceptionWithName>`, limits referenced as `Meeting.MAX_*`.
Fixture: `createMeeting` in `domain/src/testFixtures/kotlin/dev/notypie/domain/meet/MeetingTestFixtures.kt`
(defaults `startAt` to `now() + 1 day`, which is what satisfies the future-start rule). `MeetingReminder`
currently has no spec; a new one belongs next to `MeetingTest`.

### Common Patterns
- `validate(className = this.javaClass.simpleName) { notBlank { ... }; "field" of v shouldBe... }` in
  `init`; `shouldSatisfy("message") { ... }` for cross-field state-machine rules.
- Private `MutableSet` with `*Snapshot()` copies; never expose the mutable reference.
- `LocalDateTime` for meeting times, `Instant` for the reminder fire time, `UUID` business key generated
  in the constructor default.

## Dependencies

### Internal
- `domain/common/` — `validate { }` DSL
- `enums/` — `MeetingReminderStatus`

### External
`java.time`, `java.util.UUID` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
