<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-03 -->

# infrastructure/repository/meeting

## Purpose
Persistence for meetings and their participants, the reminder rows the scheduler materialises and
dispatches, and the once-per-day agenda claim. Three ports (`MeetingRepository`, `MeetingReminderRepository`,
`AgendaDispatchRepository`), three `open class *Impl`, three `Jpa*Repository` interfaces, the
`AddParticipantResult` / `RescheduleResult` outcome types, and the write-conflict classifier the application
retry uses.

## Key Files
| File | Description |
|------|-------------|
| `MeetingRepository.kt` | Port: `createNewMeeting(meeting, idempotencyKey, channel): Meeting`, `getMeeting(meetingId): MeetingDto` (throws `DatabaseException`), `getAllMeetingByUserId(userId)`, `getMeetingsByUserIdInRange(userId, startAt, endAt)`, `getParticipants(meetingId)`, `updateParticipantAttendance(meetingIdempotencyKey, userId, isAttending, absentReason, absentReasonDetail?): Int`, `participantExists(meetingIdempotencyKey, userId)`, `markMeetingCanceled(meetingUid, requesterId): Boolean`, `rescheduleMeeting(meetingUid, requesterId, newStartAt): RescheduleResult`, `addParticipants(meetingUid, requesterId, participantUserIds): AddParticipantResult` |
| `MeetingRepositoryImpl.kt` | Mapping via `toSchema` / `toDomainEntity` / `toMeetingDto`; `getMeeting` uses `throwIfSchemaNotFound`. The three writes load the managed row through `findMeetingByUidWithParticipants`, check it, mutate it and `saveAndFlush`, so the `@Version` check runs inside the call: `markMeetingCanceled` returns `false` and `rescheduleMeeting` `NotAuthorized` for missing / non-host / canceled before touching the row (no version bump); `rescheduleMeeting` returns `AlreadyAtRequestedTime` without touching the row when `startAt` already equals `newStartAt`; then `cancel()` / `reschedule(newStartAt)`, and the reschedule returns `Rescheduled(toMeetingDto())` from the same persistence context; `addParticipants` checks host and not canceled (`NOT_AUTHORIZED`), `startAt` in the future (`MEETING_STARTED`), trims / dedups / drops existing members and the host (`NO_NEW_PARTICIPANTS`), compares `participants.size + new` against `Meeting.MAX_PARTICIPANTS` (`OVER_CAPACITY`), then appends `ParticipantsSchema` rows (`ADDED`). A concurrent writer that read the same version loses with `ObjectOptimisticLockingFailureException` from the flush. `getParticipants` carries a `FIXME`: it loads the whole meeting. Takes a `Clock`: `addParticipants` decides "already started" with `LocalDateTime.now(clock)`; `JpaConfiguration` passes the context bean, or the system clock when an infrastructure-only JPA slice has none |
| `JpaMeetingRepository.kt` | JPQL reads, all `LEFT JOIN FETCH m.participants` so a meeting with no participant rows (the host picked only themselves) is still found: `findMeetingWithParticipants(meetingId)`, `findAllMeetingByUserId(userId)` and `findMeetingsByUserIdAndDateRange(userId, startAt, endAt)` (publisher OR participant via an `EXISTS` subquery — never filter the fetch-join alias, it truncates the loaded collection), `findActiveByStartAtBetween(startAt, endAt)` (`isCanceled = false`, all users), `findMeetingByUidWithParticipants(meetingUid)` (also the read behind every write); `@Modifying` `updateParticipantAttendance` (keyed by `meeting.idempotencyKey` + `userId`) and `existsParticipant`. No bulk meeting UPDATE: bulk JPQL never bumps `@Version` |
| `RescheduleResult.kt` | `sealed interface RescheduleResult`: `Rescheduled(meeting: MeetingDto)` (the row after the write, participants included), `data object AlreadyAtRequestedTime` (resubmit no-op), `data object NotAuthorized` (missing, not the host, or canceled) |
| `MeetingWriteConflict.kt` | `RuntimeException.isMeetingWriteConflict()`: `true` for any `ConcurrencyFailureException` (optimistic, pessimistic, `CannotAcquireLockException`, and MariaDB snapshot-isolation 1020, which `configurations/SnapshotIsolationExceptionTranslator` turns into `SnapshotIsolationConflictException` — a write that read the meeting before another writer committed fails the versioned UPDATE with 1020 rather than 0 rows) and for a `DataIntegrityViolationException` whose cause chain (≤ 16 levels) mentions `PARTICIPANT_UNIQUE_KEY` case-insensitively (MariaDB `for key 'uk_…'`, H2 `UK_…_INDEX_n`) — two concurrent adds of the same user; `false` for everything else |
| `AddParticipantResult.kt` | `data class AddParticipantResult(outcome, addedUserIds = [], meeting: MeetingDto? = null)`; `enum Outcome { ADDED, NO_NEW_PARTICIPANTS, OVER_CAPACITY, MEETING_STARTED, NOT_AUTHORIZED, MEETING_NOT_FOUND }`. `addedUserIds` is non-empty only for `ADDED` |
| `MeetingReminderRepository.kt` | `data class ReadyReminder(reminder: MeetingReminderDto, meetingId, meetingTitle, startAt, isCanceled, attendingUserIds)` with `isArmedFor(zone)` (`scheduledAt` equals `startAt - offset` in `zone` to the second — `scheduled_at` is a second-precision `DATETIME`, `internal fun Instant.isSameSecond`), `data class ReminderCandidateMeeting(meetingId, startAt, attendingUserIds)`; port `findActiveMeetingsInWindow(from, to)`, `ensureReminder(meetingId, offsetMinutes, scheduledAt: Instant, startAt: LocalDateTime, now: Instant): Boolean`, `reminderExists(meetingId, offsetMinutes)`, `claimReminder(reminderId, claimToken, now): Boolean`, `markReminderSent(reminderId, claimToken, sentAt)`, `markReminderFailed(reminderId, claimToken, reason, now)`, `resetStuckReminders(olderThan: Instant): Int`, `findDueBefore(before: Instant, limit): List<ReadyReminder>`, `deleteByMeetingId(meetingId): Int`, `discardReminder(reminderId, scheduledAt): Boolean` |
| `MeetingReminderRepositoryImpl.kt` | Built with the `PlatformTransactionManager`. `ensureReminder` has no transaction of its own: the find runs alone, a missing row is inserted in its own `TransactionTemplate` transaction using `getReferenceById(meetingId)` for the FK proxy, and an existing `PENDING` row at another second is moved with `realignPending` in that query's own transaction (returns `true` when moved); any other existing row returns `false`; `discardReminder` = `discardPending == 1`; CAS methods map affected rows `== 1`; `findDueBefore` projects `ReadyReminder` from the fetched meeting graph, keeping only `isAttending` participants |
| `JpaMeetingReminderRepository.kt` | Derived `findByMeetingIdAndOffsetMinutes`; JPQL `findPendingIdsBefore(before, pageable)` (ids only, to-one join to the meeting, `PENDING`, `scheduledAt <= :before`, meeting not canceled, `scheduledAt` ascending) then `findWithMeetingAndParticipantsByIdIn(ids)` (`JOIN FETCH` meeting, `LEFT JOIN FETCH` participants). Two steps is a choice, not a paging requirement: Hibernate 7.4 pages a collection-fetch query in SQL by putting the `LIMIT` inside a derived table (observed on H2; the spec runs with `fail_on_pagination_over_collection_fetch` so a regression to in-memory paging fails), so a single fetch query would page correctly too. `LEFT` so a reminder whose meeting has no participant rows is still fetched and closed out (an inner fetch would drop it while the id query keeps returning it, holding a page slot forever; none is materialized today, because a meeting nobody attends gets no reminder rows); native CAS `claimReminder(id, token, now)` (PENDING→SENDING), `markSent(id, token, sentAt)`, `markFailed(id, token, reason, now)` (both `WHERE status = 'SENDING' AND claim_token = :token`; `claimReminder` and `markSent` also require `EXISTS` an uncanceled meeting — the due read filters canceled meetings, but a cancel can commit after it, and only `markSent` runs inside the outbox transaction, so a cancel after the claim turns `markSent` into 0 rows and rolls the DMs back), `resetStuckSending(olderThan, now)`; native `realignPending(id, observedAt, scheduledAt, startAt, now)` (`PENDING`, `scheduled_at = :observedAt` and the meeting still starting at `:startAt` through `EXISTS` on `meetings`) and `discardPending(id, observedAt)` (`DELETE` of a `PENDING` row still at `:observedAt`); native `deleteByMeetingId` |
| `AgendaDispatchRepository.kt` | `data class AgendaCandidateMeeting(meetingId, title, startAt, attendingUserIds)`; port `claim(agendaDate: LocalDate): Boolean`, `findAttendingMeetingsForDay(from, to)` |
| `AgendaDispatchRepositoryImpl.kt` | `claim` = `claimAgenda == 1`; the day read reuses `JpaMeetingRepository.findActiveByStartAtBetween` |
| `JpaAgendaDispatchRepository.kt` | `JpaRepository<AgendaDispatchSchema, LocalDate>`; native `INSERT IGNORE INTO agenda_dispatch (agenda_date, created_at)` as `claimAgenda(date): Int` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `schema/` | `MeetingSchema` + `ParticipantsSchema` with the `toSchema` / `toDomainEntity` / `toMeetingDto` mappers, `MeetingReminderSchema` (+ `toMeetingReminderDto`), `AgendaDispatchSchema` (see `schema/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **Writes are load-check-mutate under `@Version`.** Cancel, reschedule and add-participant load the row,
  collapse "missing", "not the host" and "already canceled" into `false` / `NOT_AUTHORIZED` **before** any
  mutation, then change the managed entity and flush. The version check closes the TOCTOU window a bulk
  `WHERE` used to close: a concurrent cancel, reschedule or add bumps the version, so a writer that read
  the old state fails instead of adding to a canceled meeting or overwriting a reschedule. Never
  reintroduce a bulk `UPDATE meetings` (it skips `@Version`) or a forced-increment lock on the lookup (a
  rejected non-host attempt would then bump someone else's meeting).
- **The conflict surfaces inside the call, not at the caller's commit.** `saveAndFlush` throws
  `ObjectOptimisticLockingFailureException`; `application/service/meeting` runs each write in a
  `REQUIRES_NEW` transaction and retries once when `isMeetingWriteConflict()`. Keep the flush — without it
  the conflict would be deferred to whatever transaction commits last. Hibernate flushes the participant
  `INSERT`s before the `meetings` version `UPDATE`, so a same-user add race loses on the unique key rather
  than on the version; that is why the classifier also accepts that one integrity violation. Keep the
  constraint name in `PARTICIPANT_UNIQUE_KEY` (used by the entity and matching `V18`).
- **Every write is idempotent under resubmit.** A second identical cancel, reschedule or add leaves the row
  and its version alone (`false` / `AlreadyAtRequestedTime` / `NO_NEW_PARTICIPANTS`). The application relies
  on this because its isolated write can commit while the interaction request still fails.
- **`participants` counts toward the version** (`@OptimisticLock(excluded = false)` on the `mappedBy`
  collection, see `schema/`). Without it adding a participant leaves the parent row untouched and two
  concurrent adds both pass the cap.
- **`updateParticipantAttendance` returning `0` depends on the JDBC URL.** Connector/J 3.5.10 defaults to
  `useAffectedRows=false` (sets `FOUND_ROWS`), so a no-op UPDATE on a matched row returns 1 and `0` means no
  match; with `useAffectedRows=true` a no-op returns `0` too. Callers that need to tell "missing" from
  "unchanged" call `participantExists`; do not turn `0` into an error inside this package.
- **Reminder dispatch is a claim-token CAS, mirroring `repository/standup`.** Generate a fresh token per
  claim and pass the same token to `markReminderSent` / `markReminderFailed`; `false` from those means a
  recovery sweep or another tick already moved the row and the outbox-side write must be rolled back.
  `resetStuckSending` keys off `updated_at`, which every native transition sets explicitly **from the caller's
  clock** (`:now`, or `:sentAt` for SENT), never `CURRENT_TIMESTAMP` (2026-10-01): the cutoff is an app `Instant`, so a
  DB-stamped claim time would only match it while the DB session zone is UTC. Rows inserted through JPA still get a
  JVM-zone `@UpdateTimestamp`, but the sweep only reads SENDING rows, whose `updated_at` the native claim wrote.
- **`ensureReminder` is idempotent through the unique key `(meeting_id, offset_minutes)`**, not through its
  `find` check: a concurrent tick either finds the row or hits the constraint on insert, and either way the
  next materialisation tick sees exactly one row. **Never wrap the find and the write in one transaction**:
  production MariaDB runs REPEATABLE READ with `innodb_snapshot_isolation` ON, so after the find's plain read
  a `realignPending` on a row another replica claimed, realigned or deleted meanwhile fails with ER_CHECKREAD
  1020 (`JpaSystemException`) instead of matching 0 rows, and the scheduler, which absorbs only
  `DataIntegrityViolationException`, stops that tick. Each write runs first in its own transaction. Reschedule uses `deleteByMeetingId` and re-materialises.
  A pass that read the start before the reschedule can still insert after the delete; the row it leaves is
  repaired on both sides: the next `ensureReminder` for the current start moves the `PENDING` row
  (`realignPending`), and the sender drops a row that is not armed for the current start
  (`ReadyReminder.isArmedFor` → `discardReminder`). Both are CAS on the `scheduled_at` that was read, and the
  realign also requires the meeting to still start at the `startAt` it was computed from, so two replicas that
  read different starts cannot move a correct row back or delete one another replica just realigned. Rows
  that left `PENDING` are never moved or deleted.
- **`agenda_dispatch` has no token and no status**: the `LocalDate` PK plus `INSERT IGNORE` is the entire
  once-per-day guarantee. `INSERT IGNORE` is MariaDB-only and cannot be exercised on H2.
- `MAX_PARTICIPANTS` lives on the domain `Meeting` aggregate; `addParticipants` compares against that
  constant rather than hard-coding a number.
- **Zero participants is a valid meeting.** `MeetingFormInput.parseParticipants` drops the host, so a
  meeting where the host picked only themselves has no participant rows. Every read uses `LEFT JOIN FETCH`;
  an inner fetch join would make it vanish from `getMeeting`, `/meetup list`, reminders and the agenda.
- Beans: `JpaConfiguration.meetingRepository` / `meetingReminderRepository` / `agendaDispatchRepository`.
  Consumers in `:application`: `MeetingServiceImpl`, `MeetingRescheduleService`,
  `MeetingReminderSchedulingService`, `DailyAgendaSchedulingService`, `mcp/DomainReadTools`. Migrations:
  `V2` (`meeting_uid`), `V3` (`end_at`), `V5` (`meeting_reminder`), `V7` (`agenda_dispatch`), `V8`
  (`absent_reason_detail`), `V18` (`version`, participant unique key), `V21` (reset inverted `end_at`).

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.meeting.*'
```
`JpaMeetingRepositoryTest` (`@DataJpaTest`) covers save / fetch-join reads / date range / attendance update,
a zero-participant meeting through every read, and the `isCanceled = false` sweep filter;
`MeetingRepositoryWriteTest` (`@DataJpaTest`, constructs `MeetingRepositoryImpl` by hand inside a
`TransactionTemplate`) covers the add / reschedule / cancel guards, the version bump of each write, no bump
on a rejected non-host write, and three read-before-write races (add vs add, reschedule vs reschedule,
cancel vs add), the same-user add race that loses on the unique key and is classified as a conflict, and
resubmits of all three writes leaving the version alone; `MeetingWriteConflictTest` is the classifier's unit
spec; `MeetingRepositoryImplTest` is the MockK mapping spec. `MeetingReminderRepositoryImplTest` (`@DataJpaTest`) covers the canceled-meeting guard (claim after a cancel fails; a cancel between claim and `markSent` fails only that reminder's `markSent`), realign and discard (stale row moved once, claimed row untouched, discard of a row another replica realigned misses, a pass with an old start cannot move a re-armed row back, CAS miss on `observedAt`), `findDueBefore` and that the stuck sweep compares against the
claim time the caller bound (claimed at 2020-01-01Z: a cutoff a minute before resets nothing, a minute after resets it).
The rest of the reminder CAS (claim race, foreign token) and `AgendaDispatchRepository.claimAgenda` are covered only
through `:application` scheduler specs with mocked ports.

### Common Patterns
- Port + `open class *Impl` + `Jpa*Repository`; `@Transactional` on writes; boolean = `rowCount == 1`.
- `LEFT JOIN FETCH` on every read that maps participants, so mapping runs outside the persistence context
  without `LazyInitializationException`. No `DISTINCT`: Hibernate 6+ de-duplicates fetch-join roots in
  memory, and SQL `DISTINCT` only adds a sort.
- `Instant` for reminder timestamps, `LocalDateTime` for meeting `startAt` — never mix them in one query.
- Native SQL for CAS transitions; JPQL for reads; managed-entity dirty checking for meeting writes.

## Dependencies

### Internal
- `domain/meet` — `Meeting`, `Member`, `RejectReason`, `MeetingDto`, `MeetingReminderDto`,
  `MeetingReminderStatus`
- `exception/meeting/DatabaseException.kt` — `throwIfSchemaNotFound`
- `repository/meeting/schema`

### External
Spring Data JPA / Hibernate, MariaDB (`INSERT IGNORE`), H2 in tests.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
