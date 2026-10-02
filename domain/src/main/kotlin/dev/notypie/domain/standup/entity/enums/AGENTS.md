<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-01 -->

# domain/standup/entity/enums

## Purpose
The two forward-only state machines of the standup aggregate: one per session, one per member DM.

## Key Files
| File | Description |
|------|-------------|
| `SessionStatus.kt` | `COLLECTING` (awaiting answers) `→ SUMMARIZED` (cutoff reached, summary posted, `summaryMessageTs` set) or `→ SKIPPED` (host cancelled / routine deactivated before cutoff — never summarized). Both end states are terminal |
| `DispatchStatus.kt` | `PENDING → SENDING → SENT | FAILED` for one `SessionDispatch`; `SENT` means the outbox row exists, not that Slack acknowledged; `FAILED` is terminal; a failed enqueue no longer writes it (since review T18 it rolls the claim back to `PENDING`). A skip — the DM would be pointless because the session is no longer `COLLECTING` or its cutoff passed (review T19), or the routine is inactive (review T28) — is written as `PENDING → FAILED` with `failure_reason = SKIPPED_REASON_PREFIX + reason` (`"skipped: …"`), not as `SKIPPED` (review G2). `SKIPPED` is declared and read but not written in this release |

## For AI Agents

### Working In This Directory
- Transitions are compare-and-set SQL, not entity methods: `JpaSessionDispatchRepository.claimDispatch`
  (`PENDING → SENDING` with `claim_token`), `markSent`, `resetStuckSending`
  (`SENDING` past a threshold `→ PENDING`), `markSkipped` (`PENDING → FAILED` with a `skipped: ` reason); `JpaStandupSessionRepository.markSummarized`
  (`COLLECTING → SUMMARIZED`, writes `summary_message_ts`). Add a state by adding a query there.
- Constants are referenced by fully-qualified name in JPQL (`WHERE d.dmStatus =
  dev.notypie.domain.standup.entity.enums.DispatchStatus.PENDING`, `SessionStatus.COLLECTING`) and as
  string literals in native SQL (`'SENDING'`, `'SUMMARIZED'`). Renaming or moving anything here is a
  query change plus a data migration of the stored strings.
- `SKIPPED` and `SUMMARIZED` are distinct so the summary listener can refuse to re-post a skipped session.
  No code writes `SessionStatus.SKIPPED` yet; the enum reserves it.
- **A new constant is a two-release change, because rollback reads the rows.** `dm_status` is `VARCHAR(16)`,
  so a new value needs no migration, but the JPA mapping reads it with `Enum.valueOf`: a binary that does not
  know the constant fails every query that loads the row (the scheduler's dispatch reads), and after an
  automatic `rollout undo` the previous release's standups would stop for the day (review G2). So release N
  only *adds* the constant (readable, never written) and stores the meaning some older way — here a skip is
  `FAILED` + `skipped: ` reason; release N+1 starts writing it, and can roll back to N safely.
  `DispatchStatus.SKIPPED` is at step N in this release; switching `markSkipped` to write it is the follow-up
  (and the prefix stays recognisable on rows written meanwhile).
- Entity `init` blocks bind states to payload: `SessionDispatch` needs `dmSentAt` for `SENT` and a
  `failureReason` for `FAILED`; `StandupSession` needs `summaryMessageTs` for `SUMMARIZED`. A new state
  that carries data needs the matching `shouldSatisfy` guard in `../`.

### Testing Requirements
```bash
./gradlew :domain:test --tests 'dev.notypie.domain.standup.entity.StandupSessionTest'
```
That spec exercises `SUMMARIZED`, `SENT`, and `FAILED` through the entity invariants. The stored skip shape (`FAILED` + `skipped: closed`) and reading a `SKIPPED` row back are pinned in `infrastructure/.../repository/standup/StandupRepositoryImplJpaTest`. Scheduler-level
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
