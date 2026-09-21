<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/meeting

## Purpose
Specs for the meeting persistence lane in main `repository/meeting/`. Two `@DataJpaTest` specs on the shared
H2 (one for the JPA queries, one driving `MeetingRepositoryImpl.addParticipants` end to end) and one MockK
mapping spec.

## Key Files
| File | Description |
|------|-------------|
| `JpaMeetingRepositoryTest.kt` | `@DataJpaTest` on `JpaMeetingRepository`. Save with participants and fetch-join read; `findMeetingWithParticipants` present / missing; `findAllMeetingByUserId` as publisher, as participant, none; explicit `meetingUid` round-trip; `findMeetingsByUserIdAndDateRange` (window + publisher-or-participant filter, `startAt` ascending, empty window); `updateParticipantAttendance` writes `isAttending` / `absentReason` / `absentReasonDetail` and returns `0` for an unknown key; `markMeetingCanceled` and `rescheduleMeeting` guards — host → `1`, non-host → `0` and unchanged, already canceled → `0`, unknown uid → `0` |
| `AddParticipantRepositoryTest.kt` | `@DataJpaTest` injecting `JpaMeetingRepository` and constructing `MeetingRepositoryImpl` by hand. `addParticipants`: host adding a new user plus an existing member plus themselves → `ADDED` with only the new id, and the host is never inserted as a participant; non-host → `NOT_AUTHORIZED` with nothing persisted; started meeting → `MEETING_STARTED`; 20 existing participants plus one more → `OVER_CAPACITY` from the domain invariant |
| `MeetingRepositoryImplTest.kt` | MockK `JpaMeetingRepository`. `getMeeting` maps to `MeetingDto` and throws `DatabaseException` when missing; `getAllMeetingByUserId` with rows and empty; `getParticipants` returns user ids and throws when missing; `participantExists` true / false pass through |

## For AI Agents

### Working In This Directory
- **Rows commit and persist across blocks** (Kotest scopes run outside the test transaction). The specs cope
  by minting a fresh `meetingUid` / `idempotencyKey` per meeting and by using distinct `publisherId`s
  (`startIterator` in `createMeetingSchema(member =, startIterator =)`) so `findAllMeetingByUserId`
  assertions stay exact; reuse that pattern rather than adding `deleteAll`.
- **The host-only guard cases (host / non-host / already canceled / unknown) must all stay** for both
  `markMeetingCanceled` and `rescheduleMeeting`; they are the executable form of the "authorization is the
  WHERE clause" rule in main.
- `AddParticipantRepositoryTest` is the only spec that runs a `*Impl` against the real database; it exists
  because `addParticipants` mixes a load, a domain replay and a cascade save that a mock cannot pin.
  `OVER_CAPACITY` depends on `Meeting.MAX_PARTICIPANTS` in `:domain` (the fixture fills 20 rows first); a
  limit change breaks this spec by design.
- **Not covered here**: `MeetingReminderRepository` (claim / mark / reset CAS, `findPendingBefore`) and
  `AgendaDispatchRepository` (`claimAgenda`). `claimAgenda` is `INSERT IGNORE` and cannot run on H2, but the
  reminder CAS can and should get a `JpaMeetingReminderRepositoryTest`.
- `updateParticipantAttendance` returning `0` for a no-op is MariaDB behaviour that H2 does not reproduce;
  do not add an assertion that depends on it.

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
