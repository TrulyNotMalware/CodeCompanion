<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# infrastructure/repository/standup

## Purpose
Persistence for the standup bot: routines (config + members), per-day sessions with their per-member
dispatch rows and answers, and the claim-token CAS the scheduler uses to DM each member exactly once. One
port (`StandupRepository`) fronts three Spring Data interfaces.

## Key Files
| File | Description |
|------|-------------|
| `StandupRepository.kt` | Read views `ReadyDispatch(dispatch: SessionDispatchDto, sessionUid, sessionDate, cutoffAt, sessionStatus, summaryMessageTs?, routineUid)` and `NudgeCandidateSession(sessionId, sessionUid, routineUid, cutoffAt, sentMemberIds: Set, answeredUserIds: Set)`. Port: `createRoutine(routine): Routine`, `getRoutine(routineUid): RoutineDto` (throws `DatabaseException`), `findActiveRoutinesByChannel(commandChannel)`, `listActiveRoutines()`, `deactivateRoutine(routineUid): Boolean`, `createSession(session): StandupSession` (no upsert), `findSession(routineUid, sessionDate)`, `findSession(sessionUid)`, `recordAnswer(sessionUid, userId, responses, submittedAt): Boolean`, `claimDispatch(dispatchId, claimToken): Boolean`, `markDispatchSent(dispatchId, claimToken, sentAt)`, `markDispatchFailed(dispatchId, claimToken, reason)`, `resetStuckDispatches(olderThan): Int`, `findPendingDispatchesBefore(before, limit): List<ReadyDispatch>`, `findCollectingSessionsPastCutoff(before)`, `markSessionSummarized(sessionId, messageTs): Boolean`, `findCollectingSessionsForNudge(now, nudgeWindowEnd): List<NudgeCandidateSession>`, `claimNudge(sessionId): Boolean`, `replaceSummaryMessageTs(currentMessageTs, messageTs): Boolean` |
| `StandupRepositoryImpl.kt` | `open class` over `JpaRoutineRepository`, `JpaStandupSessionRepository`, `JpaSessionDispatchRepository`. `recordAnswer` removes the user's previous `StandupAnswerSchema` and appends a new one (last submission wins), joining responses with `RESPONSE_DELIMITER`; `findCollectingSessionsForNudge` derives `sentMemberIds` from dispatches with `dmStatus == SENT` only; CAS results map `== 1` |
| `JpaRoutineRepository.kt` | JPQL with `LEFT JOIN FETCH r.members`: `findByRoutineUid`, `findActiveByCommandChannel(channel)` (`DISTINCT`, `isActive = true`), `findAllActive()`; `@Modifying markInactive(routineUid)` guarded by `isActive = true` (soft delete, returns the row count) |
| `JpaStandupSessionRepository.kt` | JPQL fetching `dispatches` + `answers`: `findByRoutineUidAndSessionDate`, `findBySessionUid`, `findCollectingPastCutoff(before)`, `findCollectingForNudge(now, nudgeWindowEnd)` (`nudgedAt IS NULL AND cutoffAt > :now AND cutoffAt <= :nudgeWindowEnd`); shallow `findShallowByRoutineUidAndSessionDate`; native CAS `markSummarized(id, messageTs)` (COLLECTING→SUMMARIZED), `claimNudge(id)` (`nudged_at IS NULL AND status = 'COLLECTING'`), `replaceSummaryMessageTs(currentMessageTs, messageTs)` (`WHERE summary_message_ts = :currentMessageTs AND status = 'SUMMARIZED'`) |
| `JpaSessionDispatchRepository.kt` | JPQL `findPendingBefore(before, pageable)` (`JOIN FETCH d.session`, `PENDING`, `dmTriggerAt <= :before`); native CAS `claimDispatch(id, token)` (PENDING→SENDING), `markSent(id, token, sentAt)`, `markFailed(id, token, reason)` (both `WHERE dm_status = 'SENDING' AND claim_token = :token`), `resetStuckSending(olderThan)` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `schema/` | `RoutineSchema` + `RoutineMemberSchema`, `StandupSessionSchema` + `SessionDispatchSchema` + `StandupAnswerSchema`, with `toSchema` / `toDomainEntity` / `toRoutineDto` / `toStandupSessionDto` (see `schema/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **Dispatch is the reference claim-token CAS for the codebase** (`meeting_reminder` and the CVE summary
  worker copy it). `claimDispatch` stamps a caller-generated token; `markSent` / `markFailed` succeed only
  while that exact token is still on the row. `false` from the mark step means a recovery sweep
  (`resetStuckSending`) or another tick invalidated the claim and the outbox write must be rolled back.
- **`claimNudge` and `markSessionSummarized` are once-only CAS on the session row** (`nudged_at IS NULL`,
  `status = 'COLLECTING'`); `replaceSummaryMessageTs` is keyed on the *current* ts so a stale replacement is
  a no-op. All three return row counts the impl maps to booleans.
- **`createSession` does not upsert.** Unique `(routine_uid, session_date)` throws on a duplicate; callers
  check `findSession(routineUid, sessionDate)` first.
- **`recordAnswer` is remove-then-add on a fetched graph**, not a CAS; concurrent submissions by one user are
  last-writer-wins. `responses` are joined by `StandupSessionSchema.RESPONSE_DELIMITER` (ASCII Unit
  Separator) and split back on read — never accept that character in a question or answer.
- **`ReadyDispatch` carries the session's own `sessionDate`** so members whose local trigger time falls on a
  different calendar day than the routine zone are dispatched correctly; do not re-derive the date from
  the routine timezone in callers.
- Every session read that maps the full DTO uses `LEFT JOIN FETCH` on both `dispatches` and `answers`;
  `dispatches` is a `Set` precisely so Hibernate can fetch two collections in one query (see `schema/`).
- `deactivateRoutine` is a soft delete (`is_active = false`) and every routine read filters on it; nothing
  hard-deletes routines or sessions.
- Beans: `JpaConfiguration.standupRepository`. Consumers in `:application`: `StandupRoutineSetupService`,
  `StandupSchedulingService`, `StandupAnswerService`, `StandupSummaryService`; also `SlackOutboundStager` in
  `impl/command` (loads routine + session to open the fill modal). Migrations: `V4__add_standup_tables.sql`,
  `V6__add_standup_session_nudged_at.sql`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.standup.*'
```
**No spec exists for this lane in `:infrastructure`** — neither a `JpaStandup*RepositoryTest` on H2 nor a
`StandupRepositoryImplTest`. The CAS guards and the routine / session mapping are only exercised from
`:application` specs that mock `StandupRepository`. The missing pair: a `@DataJpaTest` racing two
`claimDispatch` calls (expect `1` then `0`), foreign-token `markSent` (expect `0`), `resetStuckSending`, and
`claimNudge` twice; plus a mapping spec for `Routine.toSchema().toDomainEntity()` and the session round-trip.

### Common Patterns
- Port + `open class *Impl` + three `Jpa*Repository`; `@Transactional` on writes; boolean = `rowCount == 1`.
- Native SQL for CAS with string status literals matching `DispatchStatus` / `SessionStatus` names; JPQL with
  fully-qualified enum constants for reads.
- `Instant` for trigger / cutoff / sent times; `LocalDate` for `sessionDate`.

## Dependencies

### Internal
- `domain/standup` — `Routine`, `RoutineMember`, `StandupSession`, `SessionDispatch`, `StandupAnswer`,
  `enums/{DispatchStatus, SessionStatus}`, DTOs `RoutineDto`, `RoutineMemberDto`, `StandupSessionDto`,
  `SessionDispatchDto`, `StandupAnswerDto`
- `exception/meeting/DatabaseException.kt` — `throwIfSchemaNotFound`
- `repository/standup/schema`

### External
Spring Data JPA / Hibernate.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
