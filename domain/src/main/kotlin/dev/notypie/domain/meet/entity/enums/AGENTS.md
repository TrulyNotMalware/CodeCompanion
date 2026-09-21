<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/meet/entity/enums

## Purpose
The reminder dispatch state machine for `MeetingReminder`: `PENDING → SENDING → SENT | FAILED`.

## Key Files
| File | Description |
|------|-------------|
| `MeetingReminderStatus.kt` | `PENDING` (row materialized, nothing sent), `SENDING` (claimed by one scheduler tick), `SENT` (outbox rows persisted — not "Slack acknowledged"), `FAILED` (terminal; `failureReason` records why). The KDoc is the authoritative description of each transition |

## For AI Agents

### Working In This Directory
- Transitions are not methods on the entity. They are executed as compare-and-set SQL in
  `infrastructure/repository/meeting/JpaMeetingReminderRepository.kt`: `claimReminder` (`PENDING → SENDING`
  with a `claim_token`), `markSent` / `markFailed` (`SENDING → SENT | FAILED`, token must match), and
  `resetStuckSending` (`SENDING` older than a threshold `→ PENDING`). Adding a state means adding a query
  there, not a `fun` here.
- The constants appear by fully-qualified name in JPQL (`WHERE r.status =
  dev.notypie.domain.meet.entity.enums.MeetingReminderStatus.PENDING`) and as string literals in native
  SQL (`'SENDING'`, `'PENDING'`). Renaming a constant or moving this package breaks those queries and the
  `EnumType.STRING` column values — it is a data migration, not a refactor.
- `FAILED` is terminal by design; the unique `(meeting_id, offset_minutes)` constraint means one attempt per
  offset. Do not add a `FAILED → PENDING` retry path without also revisiting that constraint.
- `MeetingReminder`'s `init` block ties the enum to its payload: `SENT` requires `sentAt`, `FAILED` requires a
  non-blank `failureReason`. Keep that pairing when adding states.

### Testing Requirements
No spec exercises this enum directly. The layering guard covers the package:
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.architecture.DomainLayeringGuardTest'
```
State-machine behaviour is specified in `:application:test --tests '*MeetingReminderSchedulingServiceTest'`
against `MeetingReminderDto`.

### Common Patterns
- Plain `enum class` with no payload; the KDoc carries the state diagram. Mirror that KDoc style
  (`DispatchStatus` in `standup/entity/enums/` is the sibling with identical semantics).

## Dependencies

### Internal
None.

### External
None beyond the Kotlin stdlib.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
