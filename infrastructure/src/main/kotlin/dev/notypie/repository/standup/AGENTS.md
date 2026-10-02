<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-01 -->

# infrastructure/repository/standup

## Purpose
Persistence for the standup bot: routines (config + members), per-day sessions with their per-member
dispatch rows and answers, and the claim-token CAS the scheduler uses to DM each member exactly once. One
port (`StandupRepository`) fronts three Spring Data interfaces.

## Key Files
| File | Description |
|------|-------------|
| `StandupRepository.kt` | Read views `ReadyDispatch(dispatch: SessionDispatchDto, sessionUid, sessionDate, cutoffAt, sessionStatus, summaryMessageTs?, routineUid)` and `NudgeCandidateSession(sessionId, sessionUid, routineUid, cutoffAt, sentMemberIds: Set, answeredUserIds: Set)`. Port: `createRoutine(routine): Routine`, `getRoutine(routineUid): RoutineDto` (throws `DatabaseException`), `findActiveRoutinesByChannel(commandChannel)`, `listActiveRoutines()`, `deactivateRoutine(routineUid): Boolean`, `createSession(session): StandupSession` (no upsert), `findSession(routineUid, sessionDate)`, `findSession(sessionUid)`, `findSessionForSummary(sessionUid)` (locks the session row, then maps the full graph; caller's transaction required), `recordAnswer(sessionUid, userId, responses, submittedAt): AnswerRecordResult` (`RECORDED` / `SESSION_CLOSED` / `SESSION_NOT_FOUND`), `claimDispatch(dispatchId, claimToken): Boolean`, `markDispatchSent(dispatchId, claimToken, sentAt)`, `markDispatchSkipped(dispatchId, reason): Boolean`, `resetStuckDispatches(olderThan): Int`, `findPendingDispatchesBefore(before, limit): List<ReadyDispatch>`, `findCollectingSessionsPastCutoff(before)`, `markSessionSummarized(sessionId, messageTs): Boolean`, `findCollectingSessionsForNudge(now, nudgeWindowEnd): List<NudgeCandidateSession>`, `claimNudge(sessionId): Boolean`, `replaceSummaryMessageTs(currentMessageTs, messageTs): Boolean` |
| `StandupRepositoryImpl.kt` | `open class` over `JpaRoutineRepository`, `JpaStandupSessionRepository`, `JpaSessionDispatchRepository`. `findSessionForSummary` is `@Transactional(propagation = MANDATORY)`: `findLockedBySessionUid`, then the lazy collections load under the lock. `recordAnswer` locks the session the same way first, then returns `SESSION_CLOSED` without writing when the session is not `COLLECTING` or `submittedAt` is not before `cutoffAt`; otherwise it updates the user's existing `StandupAnswerSchema` in place or appends a new one (last submission wins), joining responses with `RESPONSE_DELIMITER`; `findCollectingSessionsForNudge` derives `sentMemberIds` from dispatches with `dmStatus == SENT` only; CAS results map `== 1` |
| `JpaRoutineRepository.kt` | JPQL with `LEFT JOIN FETCH r.members`: `findByRoutineUid`, `findActiveByCommandChannel(channel)` (`DISTINCT`, `isActive = true`), `findAllActive()`; `@Modifying markInactive(routineUid)` guarded by `isActive = true` (soft delete, returns the row count) |
| `JpaStandupSessionRepository.kt` | `@Lock(PESSIMISTIC_WRITE) findLockedBySessionUid(sessionUid)` (session row only, collections lazy — `SELECT … FOR UPDATE`); JPQL fetching `dispatches` + `answers`: `findByRoutineUidAndSessionDate`, `findBySessionUid`, `findCollectingPastCutoff(before)`, `findCollectingForNudge(now, nudgeWindowEnd)` (`nudgedAt IS NULL AND cutoffAt > :now AND cutoffAt <= :nudgeWindowEnd`); shallow `findShallowByRoutineUidAndSessionDate`; native CAS `markSummarized(id, messageTs)` (COLLECTING→SUMMARIZED), `claimNudge(id)` (`nudged_at IS NULL AND status = 'COLLECTING'`), `replaceSummaryMessageTs(currentMessageTs, messageTs)` (`WHERE summary_message_ts = :currentMessageTs AND status = 'SUMMARIZED'`) |
| `JpaSessionDispatchRepository.kt` | JPQL `findPendingBefore(before, pageable)` (`JOIN FETCH d.session`, `PENDING`, `dmTriggerAt <= :before`); native CAS `claimDispatch(id, token)` (PENDING→SENDING), `markSent(id, token, sentAt)` (`WHERE dm_status = 'SENDING' AND claim_token = :token`), `markSkipped(id, reason)` (`PENDING → SKIPPED`, reason in `failure_reason`), `resetStuckSending(olderThan)` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `schema/` | `RoutineSchema` + `RoutineMemberSchema`, `StandupSessionSchema` + `SessionDispatchSchema` + `StandupAnswerSchema`, with `toSchema` / `toDomainEntity` / `toRoutineDto` / `toStandupSessionDto` (see `schema/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **Dispatch is the reference claim-token CAS for the codebase** (`meeting_reminder` and the CVE summary
  worker copy it). `claimDispatch` stamps a caller-generated token; `markSent` succeeds only while that exact
  token is still on the row. The scheduler now runs claim + outbox save + `markSent` in one transaction, so a
  failure rolls the claim back to `PENDING` (review T18); `markFailed` / `markDispatchFailed` were removed
  with that change. `resetStuckSending` stays as a safety net for rows left `SENDING` by older builds.
- **`claimNudge` and `markSessionSummarized` are once-only CAS on the session row** (`nudged_at IS NULL`,
  `status = 'COLLECTING'`); `replaceSummaryMessageTs` is keyed on the *current* ts so a stale replacement is
  a no-op. All three return row counts the impl maps to booleans.
- **The session row lock is the answer/summary handshake (review G5).** `recordAnswer` and
  `findSessionForSummary` both start with `findLockedBySessionUid`. Keep the lock the first read of each
  transaction: on MariaDB's REPEATABLE READ a plain read taken before it would fix the snapshot early and the
  summary could miss an answer committed while it waited. The lock is held until the caller commits, so the
  summary transaction must stay short (it saves one outbox row and runs one CAS).
- **`createSession` does not upsert.** Unique `(routine_uid, session_date)` throws on a duplicate; callers
  check `findSession(routineUid, sessionDate)` first.
- **`recordAnswer` updates the existing answer row in place**, not a CAS; concurrent submissions by one user
  are last-writer-wins. Never go back to remove + add: with IDENTITY ids the new row's INSERT runs at merge
  time, before the orphan DELETE flushes, so every resubmission hit `uk_standup_answer_session_user` (T9). `responses` are joined by `StandupSessionSchema.RESPONSE_DELIMITER` (ASCII Unit
  Separator) and split back on read — never accept that character in a question or answer.
- **`ReadyDispatch` carries the session's own `sessionDate`** so members whose local trigger time falls on a
  different calendar day than the routine zone are dispatched correctly; do not re-derive the date from
  the routine timezone in callers.
- Every session read that maps the full DTO uses `LEFT JOIN FETCH` on both `dispatches` and `answers`, and
  both are `Set`s so the cross product of the join collapses back to one element per row: with `answers` as a
  List every answer came back once per dispatch (review G1; see `schema/`).
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
`StandupRepositoryImplJpaTest` (H2, real Hibernate) covers the answer resubmission path. The remaining
gaps are the dispatch / nudge CAS guards and the routine mapping, still exercised only from `:application`
specs that mock `StandupRepository`. Still missing: a `@DataJpaTest` racing two
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
