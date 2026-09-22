<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-09-22 -->

# infrastructure/repository/meeting

## Purpose
Persistence for meetings and their participants, the reminder rows the scheduler materialises and
dispatches, and the once-per-day agenda claim. Three ports (`MeetingRepository`, `MeetingReminderRepository`,
`AgendaDispatchRepository`), three `open class *Impl`, three `Jpa*Repository` interfaces, plus the
`AddParticipantResult` outcome type.

## Key Files
| File | Description |
|------|-------------|
| `MeetingRepository.kt` | Port: `createNewMeeting(meeting, idempotencyKey, channel): Meeting`, `getMeeting(meetingId): MeetingDto` (throws `DatabaseException`), `getAllMeetingByUserId(userId)`, `getMeetingsByUserIdInRange(userId, startAt, endAt)`, `getParticipants(meetingId)`, `updateParticipantAttendance(meetingIdempotencyKey, userId, isAttending, absentReason, absentReasonDetail?): Int`, `participantExists(meetingIdempotencyKey, userId)`, `markMeetingCanceled(meetingUid, requesterId): Boolean`, `rescheduleMeeting(meetingUid, requesterId, newStartAt): Boolean`, `findMeetingByUid(meetingUid): MeetingDto?`, `addParticipants(meetingUid, requesterId, participantUserIds): AddParticipantResult` |
| `MeetingRepositoryImpl.kt` | Mapping via `toSchema` / `toDomainEntity` / `toMeetingDto`; `getMeeting` uses `throwIfSchemaNotFound`; `addParticipants` loads the meeting through `findMeetingByUidForUpdate` (`OPTIMISTIC_FORCE_INCREMENT`), checks host and not canceled (`NOT_AUTHORIZED`), `startAt` in the future (`MEETING_STARTED`), trims / dedups / drops existing members and the host (`NO_NEW_PARTICIPANTS`), compares `participants.size + new` against `Meeting.MAX_PARTICIPANTS` (`OVER_CAPACITY`), then appends `ParticipantsSchema` rows and saves (`ADDED`); a concurrent add loses with `ObjectOptimisticLockingFailureException` at commit. `rescheduleMeeting` reads the row first and moves `endAt` by the same delta as `startAt`. `getParticipants` carries a `FIXME`: it loads the whole meeting |
| `JpaMeetingRepository.kt` | JPQL reads: `findMeetingWithParticipants(meetingId)` (`JOIN FETCH`), `findAllMeetingByUserId(userId)` and `findMeetingsByUserIdAndDateRange(userId, startAt, endAt)` (publisher OR participant via an `EXISTS` subquery — never filter the fetch-join alias, it truncates the loaded collection), `findMeetingByUidForUpdate(meetingUid)` (`@Lock(OPTIMISTIC_FORCE_INCREMENT)`, no fetch join — the read behind `addParticipants` and `rescheduleMeeting`), `findActiveByStartAtBetween(startAt, endAt)` (`isCanceled = false`, all users), `findMeetingByUidWithParticipants(meetingUid)` (`LEFT JOIN FETCH`); `@Modifying` `updateParticipantAttendance` (keyed by `meeting.idempotencyKey` + `userId`), `existsParticipant`, `markMeetingCanceled` / `rescheduleMeeting(meetingUid, requesterId, newStartAt, newEndAt)` (`WHERE meetingUid = :meetingUid AND publisherId = :requesterId AND isCanceled = false`; `clearAutomatically` so the caller's later read in the same tx sees the new times) |
| `AddParticipantResult.kt` | `data class AddParticipantResult(outcome, addedUserIds = [], meeting: MeetingDto? = null)`; `enum Outcome { ADDED, NO_NEW_PARTICIPANTS, OVER_CAPACITY, MEETING_STARTED, NOT_AUTHORIZED, MEETING_NOT_FOUND }`. `addedUserIds` is non-empty only for `ADDED` |
| `MeetingReminderRepository.kt` | `data class ReadyReminder(reminder: MeetingReminderDto, meetingId, meetingTitle, startAt, isCanceled, attendingUserIds)`, `data class ReminderCandidateMeeting(meetingId, startAt, attendingUserIds)`; port `findActiveMeetingsInWindow(from, to)`, `ensureReminder(meetingId, offsetMinutes, scheduledAt: Instant): Boolean`, `reminderExists(meetingId, offsetMinutes)`, `claimReminder(reminderId, claimToken): Boolean`, `markReminderSent(reminderId, claimToken, sentAt)`, `markReminderFailed(reminderId, claimToken, reason)`, `resetStuckReminders(olderThan: Instant): Int`, `findDueBefore(before: Instant, limit): List<ReadyReminder>`, `deleteByMeetingId(meetingId): Int` |
| `MeetingReminderRepositoryImpl.kt` | `ensureReminder` is find-then-save using `getReferenceById(meetingId)` for the FK proxy; CAS methods map affected rows `== 1`; `findDueBefore` projects `ReadyReminder` from the fetched meeting graph, keeping only `isAttending` participants |
| `JpaMeetingReminderRepository.kt` | Derived `findByMeetingIdAndOffsetMinutes`; JPQL `findPendingBefore(before, pageable)` (`DISTINCT`, `JOIN FETCH` meeting + participants, `PENDING`, `scheduledAt <= :before`, meeting not canceled); native CAS `claimReminder(id, token)` (PENDING→SENDING), `markSent(id, token, sentAt)`, `markFailed(id, token, reason)` (both `WHERE status = 'SENDING' AND claim_token = :token`), `resetStuckSending(olderThan)`; native `deleteByMeetingId` |
| `AgendaDispatchRepository.kt` | `data class AgendaCandidateMeeting(meetingId, title, startAt, attendingUserIds)`; port `claim(agendaDate: LocalDate): Boolean`, `findAttendingMeetingsForDay(from, to)` |
| `AgendaDispatchRepositoryImpl.kt` | `claim` = `claimAgenda == 1`; the day read reuses `JpaMeetingRepository.findActiveByStartAtBetween` |
| `JpaAgendaDispatchRepository.kt` | `JpaRepository<AgendaDispatchSchema, LocalDate>`; native `INSERT IGNORE INTO agenda_dispatch (agenda_date, created_at)` as `claimAgenda(date): Int` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `schema/` | `MeetingSchema` + `ParticipantsSchema` with the `toSchema` / `toDomainEntity` / `toMeetingDto` mappers, `MeetingReminderSchema` (+ `toMeetingReminderDto`), `AgendaDispatchSchema` (see `schema/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **Host-only authorization is the WHERE clause.** `markMeetingCanceled` and `rescheduleMeeting` collapse
  "missing", "not the host" and "already canceled" into a `0` row count through
  `meetingUid = :meetingUid AND publisherId = :requesterId AND isCanceled = false`. Keep that single
  statement; a load-then-compare reintroduces a TOCTOU window. `addParticipants` is the one path that loads
  first — it needs the participant graph — and it still checks `publisherId` on the loaded row.
- **`updateParticipantAttendance` returning `0` is ambiguous** on MariaDB (`CLIENT_FOUND_ROWS=false` returns
  `0` for a no-op UPDATE too). Callers that need to tell "missing" from "unchanged" call
  `participantExists`; do not turn `0` into an error inside this package.
- **Reminder dispatch is a claim-token CAS, mirroring `repository/standup`.** Generate a fresh token per
  claim and pass the same token to `markReminderSent` / `markReminderFailed`; `false` from those means a
  recovery sweep or another tick already moved the row and the outbox-side write must be rolled back.
  `resetStuckSending` keys off `updated_at`, which every native transition sets explicitly.
- **`ensureReminder` is idempotent through the unique key `(meeting_id, offset_minutes)`**, not through its
  `find` check: a concurrent tick either finds the row or hits the constraint on insert, and either way the
  next materialisation tick sees exactly one row. Reschedule uses `deleteByMeetingId` and re-materialises
  rather than updating rows in place.
- **`agenda_dispatch` has no token and no status**: the `LocalDate` PK plus `INSERT IGNORE` is the entire
  once-per-day guarantee. `INSERT IGNORE` is MariaDB-only and cannot be exercised on H2.
- `MAX_PARTICIPANTS` lives on the domain `Meeting` aggregate; `addParticipants` replays the domain method
  instead of counting rows so the limit has one owner.
- Beans: `JpaConfiguration.meetingRepository` / `meetingReminderRepository` / `agendaDispatchRepository`.
  Consumers in `:application`: `MeetingServiceImpl`, `MeetingRescheduleService`,
  `MeetingReminderSchedulingService`, `DailyAgendaSchedulingService`, `mcp/DomainReadTools`. Migrations:
  `V2` (`meeting_uid`), `V3` (`end_at`), `V5` (`meeting_reminder`), `V7` (`agenda_dispatch`), `V8`
  (`absent_reason_detail`).

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.meeting.*'
```
`JpaMeetingRepositoryTest` (`@DataJpaTest`) covers save / fetch-join reads / date range / attendance update /
cancel and reschedule guards (host, non-host, already canceled, unknown uid); `AddParticipantRepositoryTest`
(`@DataJpaTest`, constructs `MeetingRepositoryImpl` by hand) covers all four `addParticipants` rejections
plus the happy path; `MeetingRepositoryImplTest` is the MockK mapping spec. **`MeetingReminderRepository`
and `AgendaDispatchRepository` have no spec in this module** — the reminder CAS (claim race, foreign token,
`resetStuckSending`) and `claimAgenda` are covered only through `:application` scheduler specs with mocked
ports.

### Common Patterns
- Port + `open class *Impl` + `Jpa*Repository`; `@Transactional` on writes; boolean = `rowCount == 1`.
- `JOIN FETCH` (+ `DISTINCT` when joining a to-many) on every read that maps participants, so mapping runs
  outside the persistence context without `LazyInitializationException`.
- `Instant` for reminder timestamps, `LocalDateTime` for meeting `startAt` — never mix them in one query.
- Native SQL for CAS transitions; JPQL for reads and the host-guarded updates.

## Dependencies

### Internal
- `domain/meet` — `Meeting`, `Member`, `RejectReason`, `MeetingDto`, `MeetingReminderDto`,
  `MeetingReminderStatus`
- `exception/meeting/DatabaseException.kt` — `throwIfSchemaNotFound`
- `repository/meeting/schema`

### External
Spring Data JPA / Hibernate, MariaDB (`INSERT IGNORE`), H2 in tests.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
