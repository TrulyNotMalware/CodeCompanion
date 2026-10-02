<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-01 -->

# infrastructure/repository/standup/schema

## Purpose
JPA entities for the standup lane and the mappers to and from the domain aggregates and DTOs. Two files, five
entities: the routine config with its member rows, and the session with its dispatch and answer rows.

## Key Files
| File | Description |
|------|-------------|
| `RoutineSchema.kt` | `@Entity(name = "standup_routine")`, index `idx_standup_routine_active_channel` on `(is_active, command_channel)`: `routine_uid` (UUID, unique, 36), `name` (60), `creator_id`, `command_channel`, `summary_channel`, `questions` (`TEXT`, `\n`-joined as `questionsRaw`), `trigger_local_time: LocalTime`, `cutoff_offset_seconds: Long`, `weekdays` (80, comma-joined `DayOfWeek.name` as `weekdaysRaw`), `routine_timezone` (64, IANA id), `is_active`, `members` `@OneToMany(mappedBy, LAZY, orphanRemoval = true, cascade = ALL)`, timestamps; `QUESTION_DELIMITER = "\n"`, `WEEKDAY_DELIMITER = ","`. `@Entity(name = "standup_routine_member") RoutineMemberSchema`: `@ManyToOne(LAZY) routine`, `user_id`, `user_timezone` (64), `created_at`. Mappers `Routine.toSchema()`, `RoutineSchema.toDomainEntity()`, `RoutineSchema.toRoutineDto()` |
| `StandupSessionSchema.kt` | `@Entity(name = "standup_session")`, `uk_standup_session_routine_date` on `(routine_uid, session_date)`, index on `cutoff_at`: `session_uid` (UUID, unique), `routine_uid` (UUID, plain column, no FK), `session_date: LocalDate`, `cutoff_at: Instant`, `status` `@Enumerated(STRING)` (16, default `COLLECTING`), `summary_message_ts?` (64), `nudged_at?: Instant`, `dispatches: MutableSet<SessionDispatchSchema>` and `answers: MutableSet<StandupAnswerSchema>` (both `mappedBy`, LAZY, `orphanRemoval = true`, `cascade = ALL`), timestamps; `RESPONSE_DELIMITER` = ASCII Unit Separator (U+001F). `@Entity(name = "standup_session_dispatch") SessionDispatchSchema`: unique `(session_id, user_id)`, index `(dm_status, dm_trigger_at)`; `user_id`, `dm_trigger_at: Instant`, `dm_sent_at?`, `dm_status` `@Enumerated(STRING)` (16, default `PENDING`), `failure_reason?` (`TEXT`), `claim_token?` (36), timestamps. `@Entity(name = "standup_answer") StandupAnswerSchema`: unique `(session_id, user_id)`; `user_id`, `responses` (`TEXT`, delimiter-joined as `responsesRaw`, `var`), `submitted_at: Instant` (`var`) — the two `var`s let a resubmission update the row in place (T9). Mappers `StandupSession.toSchema()`, `StandupSessionSchema.toDomainEntity()`, `StandupSessionSchema.toStandupSessionDto()` |

## For AI Agents

### Working In This Directory
- **`dispatches` and `answers` are both `MutableSet`s, and must stay Sets**: every session read `JOIN FETCH`es
  both in one query, whose SQL result is their cross product. Two bags (Lists) throw
  `MultipleBagFetchException`; a Set beside a bag does not throw but keeps each bag element once per row of the
  other collection — `answers` as a List came back with every answer once per dispatch (3 × 2 → 6), and the
  standup summary stored that duplicated list in its outbox payload (review G1). The entities keep identity
  `equals`, which is what a Set needs here: one persistence context yields one instance per row.
- **Encoded text columns**: questions are `\n`-joined (the domain rejects newlines inside a question),
  weekdays are comma-joined enum names, responses are joined by `RESPONSE_DELIMITER` (U+001F, never present
  in Slack `plain_text_input` values). Changing a delimiter is a data migration of every existing row.
- **Timezones are stored as IANA ids** (`routine_timezone`, `user_timezone`) and rebuilt with `ZoneId.of`;
  `trigger_local_time` maps to JDBC `TIME`; `cutoff_offset_seconds` is an integer for DB portability and
  maps to `Duration`.
- **`routine_uid` on the session is a plain column**, not a relation to `standup_routine`; the scheduler
  resolves routine context separately (`ReadyDispatch` / `NudgeCandidateSession` in the parent package).
- **Dispatch entities are all `val`** (answers too, except `responsesRaw` / `submittedAt`, which `recordAnswer`
  updates in place); status / token / timestamps change only through the native
  CAS statements in `JpaSessionDispatchRepository` and `JpaStandupSessionRepository`. `updated_at` is set
  explicitly inside each CAS because native updates bypass `@UpdateTimestamp`, and `resetStuckSending` ages
  off that column.
- `Routine.toSchema()` / `StandupSession.toSchema()` build the child rows pointing back at the parent and
  rely on `cascade = ALL` to persist them in one `save`; `toDomainEntity()` rebuilds through the domain
  constructors and `addMember` / `addDispatch` / `addAnswer`, so domain invariants re-run on load.
- Tables: `V4__add_standup_tables.sql`; `nudged_at` added in `V6__add_standup_session_nudged_at.sql`;
  `idx_standup_routine_active_channel` (the standup lookups filter on active routines of a channel) in
  `V18__add_meeting_version_and_indexes.sql`. An index added to a `@Table` here needs the same `CREATE INDEX` in a
  migration, because prod runs `ddl-auto: none`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.standup.*'
```
`StandupRepositoryImplJpaTest` (H2, real Hibernate) persists sessions through `StandupSession.toSchema()` and
reads them back through `toStandupSessionDto()`, with three dispatches and two answers so a duplicating fetch
fails (G1). The routine mapping still has no spec, and there are no schema builders for this lane under
`src/testFixtures/kotlin/dev/notypie/schema/`.

### Common Patterns
- Parent / child pairs in one file with the mappers as top-level extension functions.
- `@Entity(name = "<table>")` as the table name; `@Table` only for constraints and indexes.
- Enum columns `@Enumerated(STRING)` with `length = 16`.

## Dependencies

### Internal
- `domain/standup/entity` — `Routine`, `RoutineMember`, `StandupSession`, `SessionDispatch`, `StandupAnswer`,
  `enums/DispatchStatus`, `enums/SessionStatus`
- `domain/standup/dto` — `RoutineDto`, `RoutineMemberDto`, `StandupSessionDto`, `SessionDispatchDto`,
  `StandupAnswerDto`

### External
Jakarta Persistence, Hibernate timestamps, Jackson annotations.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
