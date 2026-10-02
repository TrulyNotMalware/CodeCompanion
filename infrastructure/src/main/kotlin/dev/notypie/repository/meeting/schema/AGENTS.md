<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-02 -->

# infrastructure/repository/meeting/schema

## Purpose
JPA entities for the meeting lane and the mapping functions between them and the domain `Meeting` /
`MeetingDto` / `MeetingReminderDto`. The mappers live here rather than in the `*Impl` so schema and mapping
change together.

## Key Files
| File | Description |
|------|-------------|
| `MeetingSchema.kt` | `@Entity(name = "meetings")` with `@Table(indexes = [(is_canceled, start_at), (publisher_id, start_at)])`: `meeting_uid` (UUID, unique, 36), `idempotency_key` (UUID, unique), `name`, `publisher_id`, `channel`, `reason?`, `created_at`, `updated_at?` as constructor `val`s; `start_at`, `end_at?`, `is_canceled` and `@Version version: Long` as body `var`s with protected setters, changed only through `reschedule(newStartAt)` (moves `end_at` by the same delta, or resets it to `NULL` when the stored end is not after the start) and `cancel()`; `participants: MutableList<ParticipantsSchema>` `@OptimisticLock(excluded = false) @OneToMany(mappedBy = "meeting", LAZY, orphanRemoval = false, cascade = [MERGE, PERSIST])`, so adding a participant bumps `version`. `@Entity(name = "meeting_participants") ParticipantsSchema` with `@Table(uniqueConstraints = [PARTICIPANT_UNIQUE_KEY (meeting_id, user_id)], indexes = [user_id])`; `const val PARTICIPANT_UNIQUE_KEY = "uk_meeting_participants_meeting_user"` is also what `../MeetingWriteConflict.kt` looks for in a duplicate-key error, so it must keep matching `V18`: `@ManyToOne(LAZY) meeting`, `user_id`, `is_attending = true`, `absent_reason` `@Enumerated(STRING) = ATTENDING`, `absent_reason_detail?`, timestamps. Mappers `Meeting.toSchema(idempotencyKey, channel)`, `MeetingSchema.toDomainEntity()`, `MeetingSchema.toMeetingDto()` |
| `MeetingReminderSchema.kt` | `@Entity(name = "meeting_reminder")`, `uk_meeting_reminder_meeting_offset` on `(meeting_id, offset_minutes)`, index `idx_meeting_reminder_scheduled_status` on `(status, scheduled_at)`. Columns: `@ManyToOne(LAZY) meeting`, `offset_minutes`, `scheduled_at: Instant`, `sent_at?: Instant`, `status` `@Enumerated(STRING)` (16, default `PENDING`), `failure_reason?` (`TEXT`), `claim_token?` (36), `created_at`, `updated_at?`. `MeetingReminderSchema.toMeetingReminderDto()` |
| `AgendaDispatchSchema.kt` | `@Entity(name = "agenda_dispatch")`: `@Id agenda_date: LocalDate`, `created_at`. No other state |

## For AI Agents

### Working In This Directory
- **`participants` has `orphanRemoval = false` and cascades only `MERGE` + `PERSIST`** (in-file comment:
  "Delete N+1"). Removing an element from the list does not delete the row; no delete path exists today.
  `addParticipants` relies on the cascade to insert new `ParticipantsSchema` rows on `saveAndFlush(schema)`.
- **`@OptimisticLock(excluded = false)` on `participants` is load-bearing.** Hibernate leaves `mappedBy`
  collections out of version checks by default; without the override two concurrent adds both pass the
  capacity check and a participant can be added to a meeting a concurrent transaction just canceled
  (`MeetingRepositoryWriteTest` fails without it).
- **Mutate meeting state through `reschedule` / `cancel` on a managed instance**, never through a bulk JPQL
  `UPDATE` — bulk statements do not bump `@Version`.
- **`toMeetingDto` hard-codes `reason = ""`**, and `toDomainEntity` defaults `endAt` to `startAt + 1h` and
  `reason` to `""` when null. Both are lossy on purpose; check the DTO consumer before "fixing" either.
- **`toDomainEntity` rebuilds a `Meeting` through its constructor**, so domain invariants (future `startAt`,
  `endAt` after `startAt`) re-run on load. Only `createNewMeeting` calls it; every read path uses
  `toMeetingDto`. Rows with `end_at <= start_at` from before the reschedule fix are reset by `V21`.
- **`absent_reason` is stored by enum name with no `length`** (unlike every other enum column in the module);
  `RejectReason` constants must fit the default column and must never be renamed. It is mapped `nullable = false`
  to match the non-null property; that reaches only schemas Hibernate creates, not an existing database column.
  `absent_reason_detail` takes its length from `RejectReason.MAX_DETAIL_LENGTH` (255, as V8 created it). MariaDB
  utf8mb4 counts code points there; H2 counts UTF-16 units and silently cuts a longer bound value to the width
  (`JpaMeetingRepositoryTest`), so an emoji-heavy note at the limit is cut locally but stored whole in prod.
- **`MeetingReminderSchema` is all `val`**: status, token and timestamps change only through the native
  statements in `JpaMeetingReminderRepository`. Do not add setters and mutate through the entity.
  `updated_at` is `@UpdateTimestamp` for entity saves but is set explicitly by every native CAS; the
  stuck-row sweep depends on that explicit stamp.
- Migrations: `V2__add_meeting_uid.sql`, `V3__add_meeting_end_at.sql`, `V5__add_meeting_reminder_table.sql`,
  `V7__add_agenda_dispatch_table.sql`, `V8__add_participant_absent_reason_detail.sql`,
  `V18__add_meeting_version_and_indexes.sql`, `V21__fix_inverted_meeting_end_at.sql`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.meeting.*'
```
Persisted through `JpaMeetingRepositoryTest` / `MeetingRepositoryWriteTest` on H2 with the builders in
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
