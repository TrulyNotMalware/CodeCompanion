<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/standup/entity/enums

## Purpose
The two forward-only state machines of the standup aggregate: one per session, one per member DM.

## Key Files
| File | Description |
|------|-------------|
| `SessionStatus.kt` | `COLLECTING` (awaiting answers) `→ SUMMARIZED` (cutoff reached, summary posted, `summaryMessageTs` set) or `→ SKIPPED` (host cancelled / routine deactivated before cutoff — never summarized). Both end states are terminal |
| `DispatchStatus.kt` | `PENDING → SENDING → SENT | FAILED` for one `SessionDispatch`; `SENT` means the outbox row exists, not that Slack acknowledged; `FAILED` is terminal and a retry is a new dispatch row |

## For AI Agents

### Working In This Directory
- Transitions are compare-and-set SQL, not entity methods: `JpaSessionDispatchRepository.claimDispatch`
  (`PENDING → SENDING` with `claim_token`), `markSent` / `markFailed`, `resetStuckSending`
  (`SENDING` past a threshold `→ PENDING`); `JpaStandupSessionRepository.markSummarized`
  (`COLLECTING → SUMMARIZED`, writes `summary_message_ts`). Add a state by adding a query there.
- Constants are referenced by fully-qualified name in JPQL (`WHERE d.dmStatus =
  dev.notypie.domain.standup.entity.enums.DispatchStatus.PENDING`, `SessionStatus.COLLECTING`) and as
  string literals in native SQL (`'SENDING'`, `'SUMMARIZED'`). Renaming or moving anything here is a
  query change plus a data migration of the stored strings.
- `SKIPPED` and `SUMMARIZED` are distinct so the summary listener can refuse to re-post a skipped session.
  No code writes `SKIPPED` yet; the enum reserves it.
- Entity `init` blocks bind states to payload: `SessionDispatch` needs `dmSentAt` for `SENT` and a
  `failureReason` for `FAILED`; `StandupSession` needs `summaryMessageTs` for `SUMMARIZED`. A new state
  that carries data needs the matching `shouldSatisfy` guard in `../`.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.standup.entity.StandupSessionTest'
```
That spec exercises `SUMMARIZED`, `SENT`, and `FAILED` through the entity invariants. Scheduler-level
transitions are specified in `:application:test --tests '*StandupSchedulingServiceTest'`.

### Common Patterns
- Plain `enum class`, state diagram in KDoc. `DispatchStatus` mirrors
  `meet/entity/enums/MeetingReminderStatus` exactly — keep the two in step if one changes.

## Dependencies

### Internal
None.

### External
None beyond the Kotlin stdlib.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
