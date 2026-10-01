<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-01 -->

# infrastructure/repository/standup/schema

## Purpose
JPA entities for the standup lane and the mappers to and from the domain aggregates and DTOs. Two files, five
entities: the routine config with its member rows, and the session with its dispatch and answer rows.

## Key Files
| File | Description |
|------|-------------|
| `RoutineSchema.kt` | `@Entity(name = "standup_routine")`: `routine_uid` (UUID, unique, 36), `name` (60), `creator_id`, `command_channel`, `summary_channel`, `questions` (`TEXT`, `\n`-joined as `questionsRaw`), `trigger_local_time: LocalTime`, `cutoff_offset_seconds: Long`, `weekdays` (80, comma-joined `DayOfWeek.name` as `weekdaysRaw`), `routine_timezone` (64, IANA id), `is_active`, `members` `@OneToMany(mappedBy, LAZY, orphanRemoval = true, cascade = ALL)`, timestamps; `QUESTION_DELIMITER = "\n"`, `WEEKDAY_DELIMITER = ","`. `@Entity(name = "standup_routine_member") RoutineMemberSchema`: `@ManyToOne(LAZY) routine`, `user_id`, `user_timezone` (64), `created_at`. Mappers `Routine.toSchema()`, `RoutineSchema.toDomainEntity()`, `RoutineSchema.toRoutineDto()` |
| `StandupSessionSchema.kt` | `@Entity(name = "standup_session")`, `uk_standup_session_routine_date` on `(routine_uid, session_date)`, index on `cutoff_at`: `session_uid` (UUID, unique), `routine_uid` (UUID, plain column, no FK), `session_date: LocalDate`, `cutoff_at: Instant`, `status` `@Enumerated(STRING)` (16, default `COLLECTING`), `summary_message_ts?` (64), `nudged_at?: Instant`, `dispatches: MutableSet<SessionDispatchSchema>` and `answers: MutableSet<StandupAnswerSchema>` (both `mappedBy`, LAZY, `orphanRemoval = true`, `cascade = ALL`), timestamps; `RESPONSE_DELIMITER` = ASCII Unit Separator (U+001F). `@Entity(name = "standup_session_dispatch") SessionDispatchSchema`: unique `(session_id, user_id)`, index `(dm_status, dm_trigger_at)`; `user_id`, `dm_trigger_at: Instant`, `dm_sent_at?`, `dm_status` `@Enumerated(STRING)` (16, default `PENDING`), `failure_reason?` (`TEXT`), `claim_token?` (36), timestamps. `@Entity(name = "standup_answer") StandupAnswerSchema`: unique `(session_id, user_id)`; `user_id`, `responses` (`TEXT`, delimiter-joined as `responsesRaw`), `submitted_at: Instant`. Mappers `StandupSession.toSchema()`, `StandupSessionSchema.toDomainEntity()`, `StandupSessionSchema.toStandupSessionDto()` |

## For AI Agents

### Working In This Directory
- **`dispatches` and `answers` are both `MutableSet`s, and must stay Sets**: every session read `JOIN FETCH`es
  both in one query, so the result has one row per (dispatch, answer) pair. A List (bag) keeps every repeated
  row — `answers` was a List until 2026-10-01 and came back once per dispatch (3 dispatches x 2 answers loaded
  6 answers, all copied into the summary payload) — and two Lists throw `MultipleBagFetchException`. A Set
  collapses the repeats because the persistence context returns one instance per row id.
  `JpaStandupSessionRepositoryTest` pins this.
- **Encoded text columns**: questions are `\n`-joined (the domain rejects newlines inside a question),
  weekdays are comma-joined enum names, responses are joined by `RESPONSE_DELIMITER` (U+001F, never present
  in Slack `plain_text_input` values). Changing a delimiter is a data migration of every existing row.
- **Timezones are stored as IANA ids** (`routine_timezone`, `user_timezone`) and rebuilt with `ZoneId.of`;
  `trigger_local_time` maps to JDBC `TIME`; `cutoff_offset_seconds` is an integer for DB portability and
  maps to `Duration`.
- **`routine_uid` on the session is a plain column**, not a relation to `standup_routine`; the scheduler
  resolves routine context separately (`ReadyDispatch` / `NudgeCandidateSession` in the parent package).
- **Dispatch entities are all `val`**; status / token / timestamps change only through the native CAS statements
  in `JpaSessionDispatchRepository` and `JpaStandupSessionRepository`. An answer's `responsesRaw` and
  `submittedAt` are `protected set` and change only through `StandupSessionSchema.replaceAnswer`, which revises
  the member's existing row in place: a remove-then-add persists the new row (IDENTITY ids insert immediately)
  before the orphan delete runs at flush, so a second answer hit `uk_standup_answer_session_user` (reproduced on
  H2 by `StandupRepositoryImplTest`, 2026-10-01). `updated_at` is set
  explicitly inside each CAS because native updates bypass `@UpdateTimestamp`, and `resetStuckSending` ages
  off that column.
- `Routine.toSchema()` / `StandupSession.toSchema()` build the child rows pointing back at the parent and
  rely on `cascade = ALL` to persist them in one `save`; `toDomainEntity()` rebuilds through the domain
  constructors and `addMember` / `addDispatch` / `addAnswer`, so domain invariants re-run on load.
- Tables: `V4__add_standup_tables.sql`; `nudged_at` added in `V6__add_standup_session_nudged_at.sql`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.standup.*'
```
`JpaStandupSessionRepositoryTest` persists a session through `StandupSession.toSchema()` and maps it back
(each answer once), `StandupRepositoryImplTest` records a member's answer twice (one row, latest responses), and
`StandupDispatchSweepTest` covers the clock-bound stuck sweep. Sessions are built from the domain testFixtures
(`createStandupSession`, `createSessionDispatch`, `createStandupAnswer`); there are no schema builders for this
lane under `src/testFixtures/kotlin/dev/notypie/schema/`, and routines are not persisted by any spec.

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
