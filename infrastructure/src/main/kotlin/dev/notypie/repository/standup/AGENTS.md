<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-02 -->

# infrastructure/repository/standup

## Purpose
Persistence for the standup bot: routines (config + members), per-day sessions with their per-member
dispatch rows and answers, and the claim-token CAS the scheduler uses to DM each member exactly once. One
port (`StandupRepository`) fronts three Spring Data interfaces.

## Key Files
| File | Description |
|------|-------------|
| `StandupRepository.kt` | Read views `ReadyDispatch(dispatch: SessionDispatchDto, sessionUid, sessionDate, cutoffAt, sessionStatus, summaryMessageTs?, routineUid)` and `NudgeCandidateSession(sessionId, sessionUid, routineUid, cutoffAt, sentMemberIds: Set, answeredUserIds: Set)`. Port: `createRoutine(routine): Routine`, `getRoutine(routineUid): RoutineDto` (throws `DatabaseException`), `findActiveRoutinesByChannel(commandChannel)`, `listActiveRoutines()`, `deactivateRoutine(routineUid): Boolean`, `createSession(session): StandupSession` (no upsert), `findSession(routineUid, sessionDate)`, `findSession(sessionUid)`, `findSessionForSummary(sessionUid)` (row-locked, `Propagation.MANDATORY`), `recordAnswer(sessionUid, userId, responses, submittedAt): AnswerRecordResult` (`RECORDED` / `SESSION_CLOSED` / `SESSION_NOT_FOUND`), `claimDispatch(dispatchId, claimToken, now): Boolean`, `markDispatchSent(dispatchId, claimToken, sentAt)`, `recordDispatchFailure(dispatchId, reason, now)`, `markDispatchSkipped(dispatchId, reason, now): Boolean` (stores `FAILED` + `"skipped: " + reason`), `resetStuckDispatches(olderThan): Int`, `findPendingDispatchesBefore(before, limit): List<ReadyDispatch>`, `findCollectingSessionsPastCutoff(before)`, `markSessionSummarized(sessionId, messageTs): Boolean`, `findCollectingSessionsForNudge(now, nudgeWindowEnd): List<NudgeCandidateSession>`, `claimNudge(sessionId): Boolean`, `replaceSummaryMessageTs(currentMessageTs, messageTs): Boolean` |
| `StandupRepositoryImpl.kt` | `open class` over `JpaRoutineRepository`, `JpaStandupSessionRepository`, `JpaSessionDispatchRepository`. `recordAnswer` takes the session row lock (`findLockedBySessionUid`), re-reads the status under it (`findLockedStatus`), answers `SESSION_CLOSED` when the session is not `COLLECTING` or `submittedAt` is not before `cutoffAt`, and otherwise upserts the member's row natively (responses joined with `RESPONSE_DELIMITER`, last submission wins); `findSessionForSummary` takes the same lock and maps the session with the re-read status; `findCollectingSessionsForNudge` derives `sentMemberIds` from dispatches with `dmStatus == SENT` only; CAS results map `== 1` |
| `JpaRoutineRepository.kt` | JPQL with `LEFT JOIN FETCH r.members`: `findByRoutineUid`, `findActiveByCommandChannel(channel)` (`DISTINCT`, `isActive = true`), `findAllActive()`; `@Modifying markInactive(routineUid)` guarded by `isActive = true` (soft delete, returns the row count) |
| `JpaStandupSessionRepository.kt` | JPQL fetching `dispatches` + `answers`: `findByRoutineUidAndSessionDate`, `findBySessionUid`, `findCollectingPastCutoff(before)`, `findCollectingForNudge(now, nudgeWindowEnd)` (`nudgedAt IS NULL AND cutoffAt > :now AND cutoffAt <= :nudgeWindowEnd`); shallow `findShallowByRoutineUidAndSessionDate`; `@Lock(PESSIMISTIC_WRITE)` `findLockedBySessionUid` (session row only, collections lazy), native `findLockedStatus(id)` (`SELECT status … FOR UPDATE`), native `upsertAnswer(sessionId, userId, responses, submittedAt)` (`INSERT … ON DUPLICATE KEY UPDATE` on `uk_standup_answer_session_user`, MariaDB syntax), native CAS `markSummarized(id, messageTs)` (COLLECTING→SUMMARIZED), `claimNudge(id)` (`nudged_at IS NULL AND status = 'COLLECTING'`), `replaceSummaryMessageTs(currentMessageTs, messageTs)` (`WHERE summary_message_ts = :currentMessageTs AND status = 'SUMMARIZED'`) |
| `JpaSessionDispatchRepository.kt` | JPQL `findPendingBefore(before, pageable)` (`JOIN FETCH d.session`, `PENDING`, `dmTriggerAt <= :before`); native CAS `claimDispatch(id, token, now)` (PENDING→SENDING), `markSent(id, token, sentAt)`, `recordFailure(id, reason, now)` (`failure_reason` on a row that is `PENDING` again after a rolled-back claim), `markSkipped(id, reason, now)` (`PENDING → FAILED`, guarded by `dm_status = 'PENDING'`), `resetStuckSending(olderThan, now)` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `schema/` | `RoutineSchema` + `RoutineMemberSchema`, `StandupSessionSchema` + `SessionDispatchSchema` + `StandupAnswerSchema`, with `toSchema` / `toDomainEntity` / `toRoutineDto` / `toStandupSessionDto` (see `schema/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **Dispatch is the reference claim-token CAS for the codebase** (`meeting_reminder` and the CVE summary
  worker copy it). `claimDispatch` stamps a caller-generated token; `markSent` succeeds only
  while that exact token is still on the row. `false` from the mark step means a recovery sweep
  (`resetStuckSending`) or another tick invalidated the claim and the outbox write must be rolled back.
  Every native transition writes `updated_at` from the caller's clock (`:now`, or `:sentAt` for SENT), never
  `CURRENT_TIMESTAMP`, so the sweep's `Instant` cutoff and the claim time share one clock whatever the DB session
  zone is (2026-10-01).
- **`claimNudge` and `markSessionSummarized` are once-only CAS on the session row** (`nudged_at IS NULL`,
  `status = 'COLLECTING'`); `replaceSummaryMessageTs` is keyed on the *current* ts so a stale replacement is
  a no-op. All three return row counts the impl maps to booleans.
- **`createSession` does not upsert.** Unique `(routine_uid, session_date)` throws on a duplicate; callers
  check `findSession(routineUid, sessionDate)` first.
- **Answers and the summary are serialized on the session row lock.** `recordAnswer` and
  `findSessionForSummary` both start with `SELECT … FOR UPDATE` on the session, so an answer either commits
  before the summary reads the answers or waits and then finds the session `SUMMARIZED` (`SESSION_CLOSED`, the
  member is told it was not recorded). The summary read must run inside the transaction that saves the summary
  and flips the status (`MANDATORY`). A session already in the caller's persistence context keeps its stale
  `status` after the locking query (Hibernate does not overwrite a managed instance), so the status is re-read
  with `findLockedStatus`; a `refresh` would cascade into both collections and lock their rows too.
- **The answer write is a native upsert**, not "find the member's row, update or add": that decision was made
  from whatever the transaction last saw, and two first submissions by one member both inserted and hit the
  unique key; a remove-then-add inserted before the orphan delete. `INSERT … ON DUPLICATE KEY UPDATE` is
  MariaDB syntax — H2 runs it only in `MODE=MariaDB`, which the repository spec uses. `responses` are joined by
  `StandupSessionSchema.RESPONSE_DELIMITER` (ASCII Unit Separator) and split back on read — never accept that
  character in a question or answer.
- **`ReadyDispatch` carries the session's own `sessionDate`** so members whose local trigger time falls on a
  different calendar day than the routine zone are dispatched correctly; do not re-derive the date from
  the routine timezone in callers.
- Every session read that maps the full DTO uses `LEFT JOIN FETCH` on both `dispatches` and `answers`;
  both collections are `Set`s so the per-pair repeated rows collapse (see `schema/`).
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
`JpaStandupSessionRepositoryTest` (H2) pins that a session fetched with both collections maps each answer once;
`StandupRepositoryImplTest` (H2 in `MODE=MariaDB`, real transactions on two threads) covers the answer upsert,
the closed-session rejection, the stale managed session and the answer/summary lock handshake;
`StandupDispatchSweepTest` (`@DataJpaTest`) covers that the stuck sweep compares against the claim time the
caller bound. **Not provable on H2**: InnoDB `REPEATABLE READ` snapshots, gap/next-key locking and the real
`ON DUPLICATE KEY UPDATE` path need MariaDB. The CAS guards and the routine / session mapping are only exercised from
`:application` specs that mock `StandupRepository`. The missing pair: a `@DataJpaTest` racing two
`claimDispatch` calls (expect `1` then `0`), foreign-token `markSent` (expect `0`), `resetStuckSending`, and
`claimNudge` twice; plus a mapping spec for `Routine.toSchema().toDomainEntity()` and the session round-trip.

### Common Patterns
- Port + `open class *Impl` + three `Jpa*Repository`; `@Transactional` on writes; boolean = `rowCount == 1`.
- Native SQL for CAS with string status literals matching `DispatchStatus` / `SessionStatus` names (`NativeQueryStatusLiteralTest` (test `repository/`) fails when a quoted name is not a constant of the column's enum); JPQL with
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
