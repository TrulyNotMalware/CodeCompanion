<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# infrastructure/repository/meeting/schema

## Purpose
JPA entities for the meeting lane and the mapping functions between them and the domain `Meeting` /
`MeetingDto` / `MeetingReminderDto`. The mappers live here rather than in the `*Impl` so schema and mapping
change together.

## Key Files
| File | Description |
|------|-------------|
| `MeetingSchema.kt` | `@Entity(name = "meetings")`: `meeting_uid` (UUID, unique, 36), `idempotency_key` (UUID, unique), `name`, `start_at`, `end_at?`, `is_canceled`, `publisher_id`, `channel`, `reason?`, `created_at`, `updated_at?`; `participants: MutableList<ParticipantsSchema>` `@OneToMany(mappedBy = "meeting", LAZY, orphanRemoval = false, cascade = [MERGE, PERSIST])`. `@Entity(name = "meeting_participants") ParticipantsSchema`: `@ManyToOne(LAZY) meeting`, `user_id`, `is_attending = true`, `absent_reason` `@Enumerated(STRING) = ATTENDING`, `absent_reason_detail?`, timestamps. Mappers `Meeting.toSchema(idempotencyKey, channel)`, `MeetingSchema.toDomainEntity()`, `MeetingSchema.toMeetingDto()` |
| `MeetingReminderSchema.kt` | `@Entity(name = "meeting_reminder")`, `uk_meeting_reminder_meeting_offset` on `(meeting_id, offset_minutes)`, index `idx_meeting_reminder_scheduled_status` on `(status, scheduled_at)`. Columns: `@ManyToOne(LAZY) meeting`, `offset_minutes`, `scheduled_at: Instant`, `sent_at?: Instant`, `status` `@Enumerated(STRING)` (16, default `PENDING`), `failure_reason?` (`TEXT`), `claim_token?` (36), `created_at`, `updated_at?`. `MeetingReminderSchema.toMeetingReminderDto()` |
| `AgendaDispatchSchema.kt` | `@Entity(name = "agenda_dispatch")`: `@Id agenda_date: LocalDate`, `created_at`. No other state |

## For AI Agents

### Working In This Directory
- **`participants` has `orphanRemoval = false` and cascades only `MERGE` + `PERSIST`** (in-file comment:
  "Delete N+1"). Removing an element from the list does not delete the row; no delete path exists today.
  `addParticipants` relies on `PERSIST` cascading new `ParticipantsSchema` rows on `save(schema)`.
- **`toMeetingDto` hard-codes `reason = ""`**, and `toDomainEntity` defaults `endAt` to `startAt + 1h` and
  `reason` to `""` when null. Both are lossy on purpose; check the DTO consumer before "fixing" either.
- **`toDomainEntity` rebuilds a `Meeting` through its constructor**, so domain invariants re-run on load.
  The `addParticipants` comment notes `Meeting` requires a future `startAt`, which is why the
  `MEETING_STARTED` check precedes the capacity replay there; only `createNewMeeting` and `addParticipants`
  call `toDomainEntity`, every read path uses `toMeetingDto`.
- **`absent_reason` is stored by enum name with no `length`** (unlike every other enum column in the module);
  `RejectReason` constants must fit the default column and must never be renamed.
- **`MeetingReminderSchema` is all `val`**: status, token and timestamps change only through the native
  statements in `JpaMeetingReminderRepository`. Do not add setters and mutate through the entity.
  `updated_at` is `@UpdateTimestamp` for entity saves but is set explicitly by every native CAS; the
  stuck-row sweep depends on that explicit stamp.
- Migrations: `V2__add_meeting_uid.sql`, `V3__add_meeting_end_at.sql`, `V5__add_meeting_reminder_table.sql`,
  `V7__add_agenda_dispatch_table.sql`, `V8__add_participant_absent_reason_detail.sql`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.meeting.*'
```
Persisted through `JpaMeetingRepositoryTest` / `AddParticipantRepositoryTest` on H2 with the builders in
`src/testFixtures/kotlin/dev/notypie/schema/MeetingSchemaCreator.kt` (`createMeetingSchema`,
`createParticipants`, `createMeetingSchemaWithParticipant`). The mappers are pinned indirectly by
`MeetingRepositoryImplTest`; `MeetingReminderSchema` and `AgendaDispatchSchema` are never persisted in a
spec of this module.

### Common Patterns
- Bidirectional `@OneToMany(mappedBy)` / `@ManyToOne(LAZY)`, with the child constructed with its parent
  reference and appended to the parent's list before `save`.
- Mapping as top-level extension functions on the schema / domain type, in the schema file.
- `@JsonProperty("created_at")` on timestamp columns for the JSON / CDC path.

## Dependencies

### Internal
- `domain/meet/entity` — `Meeting`, `RejectReason`, `MeetingReminder`, `enums/MeetingReminderStatus`
- `domain/meet/dto` — `MeetingDto`, `MeetingParticipantDto`, `MeetingReminderDto`

### External
Jakarta Persistence, Hibernate timestamps, Jackson annotations.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
