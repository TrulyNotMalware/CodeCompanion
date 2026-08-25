<!-- Parent: ../../../../../../../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# domain/meet

## Purpose
The meeting aggregate. Models a team meeting requested through Slack — its host, participants,
schedule window, cancellation state — plus the pre-meeting reminder batches the scheduler fires.
Pure Kotlin: no persistence annotations, no Slack types. JPA schemas mirroring these entities live in
`infrastructure/repository/meeting/schema/`.

## Key Files
| File | Description |
|------|-------------|
| `entity/Meeting.kt` | `Meeting` aggregate root + `Member`; enforces `MAX_PARTICIPANTS = 20`, `MAX_TITLE_LENGTH = 20`, `MAX_REASON_LENGTH = 200`, future `startAt`, `endAt > startAt` |
| `entity/MeetingReminder.kt` | One reminder batch per `(meetingId, offsetMinutes)`; carries the pre-computed absolute UTC `scheduledAt` and a `PENDING → SENDING → SENT/FAILED` state machine |
| `entity/RejectReason.kt` | Reason enum for a participant declining attendance |
| `entity/enums/MeetingReminderStatus.kt` | `PENDING` / `SENDING` / `SENT` / `FAILED` |
| `dto/MeetingDto.kt` | Read-model DTOs crossing the repository boundary |
| `dto/MeetingReminderDto.kt` | Reminder read-model DTO for the scheduler |

## For AI Agents

### Working In This Directory
- **Invariants live in `init { validate { ... } }`**, not at call sites. This is deliberate: JPA loads
  go through the same constructor, so a logically-impossible row cannot be resurrected from disk.
  When you add a state field, add the corresponding `shouldSatisfy` guard here.
- `Meeting` exposes `participants` only through `memberSnapshot()` / `memberIdSnapshot()` copies —
  never widen that to a mutable reference.
- **Host-only authorization is *not* enforced here.** Cancel / reschedule / add-participant checks are
  enforced atomically in the repository's `WHERE` clause (`infrastructure/repository/meeting/`), with
  the UI merely hiding the button. Do not duplicate that check as a domain guard — you would create a
  TOCTOU-shaped second source of truth.
- `MeetingReminder.SENT` means "outbox rows enqueued", **not** "Slack acknowledged". Delivery is the
  outbox relay's job.
- This package must not import `dev.notypie.domain.command` — the architecture guard test enforces it.

### Testing Requirements
```bash
./gradlew :domain:test --tests '*Meeting*'
```
Specs: `domain/src/test/kotlin/dev/notypie/domain/meet/entity/MeetingTest.kt`.
Fixtures: `domain/src/testFixtures/kotlin/dev/notypie/domain/meet/` — `MeetingTestFixtures`,
`MeetingDtoCreator`, `MeetingReminderDtoCreator`. Prefer extending a fixture over inlining a new
`Meeting(...)` literal, so a constructor change lands in one place.

### Common Patterns
- Constructor-validated immutable-ish aggregates; mutable collections are private with snapshot accessors.
- Times: `LocalDateTime` for user-facing scheduling, `Instant` for absolute scheduler fire times.
- `UUID` business keys (`meetingUid`) generated in the domain, separate from any database PK.

## Dependencies

### Internal
- `domain/common/` — `validate { }` DSL

### External
`java.time`, `java.util` only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
