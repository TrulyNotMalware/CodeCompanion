<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-09-30 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/meeting

## Purpose
Specs for the meeting persistence lane in main `repository/meeting/`. Two `@DataJpaTest` specs on the shared
H2 (one for the JPA queries, one driving the `MeetingRepositoryImpl` writes end to end, including
optimistic-lock races), one `@DataJpaTest` for `MeetingReminderRepositoryImpl` in its own context, one MockK
mapping spec, and the plain unit spec of the write-conflict classifier.

## Key Files
| File | Description |
|------|-------------|
| `JpaMeetingRepositoryTest.kt` | `@DataJpaTest` on `JpaMeetingRepository`. Save with participants and fetch-join read; `findMeetingWithParticipants` present / missing; `findAllMeetingByUserId` as publisher, as participant, none; explicit `meetingUid` round-trip; `findMeetingsByUserIdAndDateRange` (window + publisher-or-participant filter, `startAt` ascending, empty window); `updateParticipantAttendance` writes `isAttending` / `absentReason` / `absentReasonDetail` and returns `0` for an unknown key; the generated `absent_reason_detail` column is exactly `RejectReason.MAX_DETAIL_LENGTH` wide (H2 `INFORMATION_SCHEMA`) and a note of that length is stored intact (the overflow side is not asserted: H2 truncates the value Hibernate casts to the column type, strict MariaDB rejects it); a zero-participant meeting found by all five reads (`LEFT JOIN FETCH`); `findActiveByStartAtBetween` excludes a canceled meeting and returns each active meeting once with its full participant list |
| `MeetingRepositoryWriteTest.kt` | `@DataJpaTest` injecting `JpaMeetingRepository` + `PlatformTransactionManager`, constructing `MeetingRepositoryImpl` by hand and wrapping each call in a `TransactionTemplate` (the proxy-less instance has no `@Transactional`). `addParticipants`: new user only / host never inserted / version bumped to 1; first participant on a zero-participant meeting; non-host → `NOT_AUTHORIZED`, nothing persisted, version 0; started → `MEETING_STARTED`; 20 + 1 → `OVER_CAPACITY`. `rescheduleMeeting`: `Rescheduled`, duration preserved and version 1; inverted stored end → `endAt` null; resubmitted with the time it already has → `AlreadyAtRequestedTime`, times and version 1 unchanged; non-host / canceled / unknown → `NotAuthorized` with version 0. Resubmitted add of the same user → `NO_NEW_PARTICIPANTS`, version stays 1. `markMeetingCanceled`: flag + version 1, resubmitted call `false` with version still 1; non-host → unchanged, version 0; unknown → `false`. Races via `raceAfterBothRead` (both workers load the row, latch, then the first writes and commits before the second writes): add vs add at 19/20, reschedule vs reschedule, cancel vs add — the second always fails with `ObjectOptimisticLockingFailureException` and the first's state wins; add vs add of the **same** user — the second fails on the participant unique key with `DataIntegrityViolationException`, which `isMeetingWriteConflict()` classifies as retryable |
| `MeetingReminderRepositoryTest.kt` | `@DataJpaTest(properties = hibernate.query.fail_on_pagination_over_collection_fetch=true)` — its own cached context and H2, so in-memory paging of a collection fetch throws instead of warning. Constructs `MeetingReminderRepositoryImpl` by hand, arms reminders through `ensureReminder` in a `TransactionTemplate`, and empties `meeting_reminder` (`deleteAllInBatch`) at the start of each block that reads "everything due" (it is the only writer of that table). `findDueBefore`: six meetings (two with several attendees and a decliner, one canceled, one with no participant rows, one not yet due) → `limit = 2` returns `first`, `second` in `scheduledAt` order with their full attending lists (decliner dropped); `limit = 10` returns `first`, `second`, `third`, `solo` (canceled and future excluded, the participant-less meeting included with no attendees); a cutoff before every row returns nothing. Review M6: re-arming a PENDING row for another time moves `scheduledAt` (returns `true`), re-arming it for the same time returns `false`; a claimed (SENDING) row is neither re-aligned nor discarded and keeps its time; `discardReminder` deletes a PENDING row so `reminderExists` is `false`. Review M7: a meeting canceled before the claim → `claimReminder` `false`, the row stays PENDING; two rows claimed while active, then one meeting canceled → its `markReminderSent` is `false` (row stays SENDING, the caller rolls back the DMs) while the other's is `true` and SENT |
| `MeetingWriteConflictTest.kt` | Plain `BehaviorSpec`. `isMeetingWriteConflict`: `ObjectOptimisticLockingFailureException`, `PessimisticLockingFailureException`, `CannotAcquireLockException` → `true`; `DataIntegrityViolationException` naming `uk_meeting_participants_meeting_user` in a cause (MariaDB wording) or in its own message (H2 wording) → `true`, a not-null violation → `false`; a non-integrity exception whose message names the key, and `IllegalStateException` → `false` |
| `MeetingRepositoryImplTest.kt` | MockK `JpaMeetingRepository`. `getMeeting` maps to `MeetingDto` and throws `DatabaseException` when missing; `getAllMeetingByUserId` with rows and empty; `getParticipants` returns user ids and throws when missing; `participantExists` true / false pass through; `rescheduleMeeting` mutates the loaded schema (90-minute duration kept, null end kept null) and `saveAndFlush`es it, non-host and missing → `NotAuthorized` without a flush, already at the requested time → `AlreadyAtRequestedTime` without a flush; `markMeetingCanceled` flags and flushes, already canceled → `false` |

## For AI Agents

### Working In This Directory
- **Rows commit and persist across blocks** (Kotest scopes run outside the test transaction). The specs cope
  by minting a fresh `meetingUid` / `idempotencyKey` per meeting and by using distinct `publisherId`s
  (`startIterator` in `createMeetingSchema(member =, startIterator =)`) so `findAllMeetingByUserId`
  assertions stay exact; reuse that pattern rather than adding `deleteAll`.
- **The guard cases (host / non-host / already canceled / unknown) and the "version untouched on
  rejection" assertions must all stay** for the three writes; they pin that rejections happen before any
  version-affecting mutation.
- **Race cases read the row in the test, not in the impl.** Each worker loads the meeting inside its own
  transaction, meets the other on a latch, and only then calls the impl, which returns the already-managed
  (stale) instance. Calling the impl first would make the second flush block on the first's row lock and
  deadlock the latch. Latch waits are `check`ed and futures use `get(10, SECONDS)`.
- `MeetingRepositoryWriteTest` is the only spec that runs a `*Impl` against the real database; it exists
  because the writes mix a load, a guard, a cascade and a version check that a mock cannot pin.
  `OVER_CAPACITY` depends on `Meeting.MAX_PARTICIPANTS` in `:domain` (the fixture fills 20 rows first); a
  limit change breaks this spec by design.
- **Not covered here**: `AgendaDispatchRepository` (`claimAgenda`, `INSERT IGNORE`, cannot run on H2) and the
  reminder claim race / foreign token / `resetStuckSending`, which belong in `MeetingReminderRepositoryTest`.
- `MeetingReminderRepositoryTest` must keep its `fail_on_pagination_over_collection_fetch` property: it is what
  makes "the batch is paged in SQL" an assertion rather than a log line.
- `updateParticipantAttendance` returning `0` for a no-op happens only on a MariaDB URL with
  `useAffectedRows=true` (Connector/J 3.5.10 defaults to found rows); H2 does not reproduce it, so do not add
  an assertion that depends on it.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.meeting.*'
```
`@DataJpaTest` specs boot `TestApplication` and share the cached H2 context with `repository/cve`.

### Common Patterns
- Builders from `testFixtures/.../schema/MeetingSchemaCreator.kt`: `createMeetingSchema(...)`,
  `createMeetingSchema(member =, startIterator =)`, `createParticipants(meeting =, userId =)`,
  `createMeetingSchemaWithParticipant(publisherId =, participantUserId =, name =, startAt =)`.
- `repository.save(schema)` then a re-read through the fetch-join query to assert persisted state.
- `BehaviorSpec` everywhere; `shouldThrow<DatabaseException>` for missing rows in the mapping spec.

## Dependencies

### Internal
- `infrastructure/src/main/kotlin/dev/notypie/repository/meeting/` (+ `schema/`)
- `infrastructure/src/main/kotlin/dev/notypie/exception/meeting/DatabaseException.kt`
- `infrastructure/src/testFixtures/kotlin/dev/notypie/schema/MeetingSchemaCreator.kt`
- `domain/meet/entity/RejectReason`; `domain/src/testFixtures/.../Constants.kt` (`TEST_USER_ID`)

### External
Kotest + `kotest-extensions-spring`, MockK, `spring-boot-starter-data-jpa-test`, H2.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
