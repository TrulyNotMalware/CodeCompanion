<!-- Parent: ../../../../../../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# infrastructure/repository

## Purpose
Persistence for every aggregate, plus the transactional outbox that carries all outbound messages.
Each lane follows the same three-part shape: a Spring Data `Jpa*Repository` holding the queries, a
`*RepositoryImpl` mapping between JPA schema classes and domain entities/DTOs, and a `schema/`
subpackage of `@Entity` classes.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `outbox/` | The transactional outbox: `MessageOutboxRepository`, `OutboundMessagePort`, `OutboundMessageCodec`, `OutboundEnvelope`, `Transport`, `schema/` (`OutboxMessage`, `MessageStatus`, `OutboxSchemaVersion`), `dto/OutboxMessageEvents` |
| `meeting/` | Meetings, participants, agenda dispatch, reminders; `AddParticipantResult` |
| `standup/` | Routines, sessions, per-member dispatches |
| `cve/` | Topics, events, subscriptions, deliveries, collect ledger |
| `agent/` | `agent_session` and `agent_turn_history` (token usage, duration, outcome) |
| `authorization/` | `user_command_role` grants backing the role system |
| `mcp/` | `mcp_tool_call_history` audit trail |

## For AI Agents

### Working In This Directory
- **The outbox row is transport-neutral.** `OutboundMessagePort.toRow` codec-encodes an
  `OutboundEnvelope` (message + `CommandBasicInfo`) into the row's single `payload` column; rendering
  to a wire payload happens later, at deliver time. The port is a **pure value builder** — it persists
  nothing; each caller writes the row through its own repository inside its own transaction, which is
  what makes the outbox commit atomically with the domain write.
- **`OutboundMessageCodec` is where Jackson polymorphism lives**, via mix-ins on the codec's own mapper,
  precisely so the domain stays annotation-free. `OpenModal` and `DirectMessage` are deliberately
  **unregistered**: neither is outbox-bound, so encoding or decoding one fails fast with an unresolved
  type id instead of silently round-tripping. Do not "complete" the subtype list.
- **`OutboxSchemaVersion` is the compatibility gate.** Writers stamp `CURRENT`; readers validate against
  `SUPPORTED` before decoding, so a relay binary that cannot parse a shape leaves a stuck row (which the
  health indicator surfaces) rather than sending a malformed request. Bump `CURRENT` **and** extend
  `SUPPORTED` for a new shape; remove a version from `SUPPORTED` only after a guaranteed-drained
  migration window.
- **Status transitions are atomic CAS, not read-then-write.** `claimPending`'s `WHERE status = 'PENDING'`
  is the source of truth and it returns the number of rows actually transitioned; dispatch only what you
  claimed. `updated_at` is touched on claim so health indicators can age `IN_PROGRESS` rows from claim
  time. The CVE lane uses the same discipline: claim-token CAS for summaries, `insertIgnore` for
  ingestion, `resetStuck` for crash recovery, and a window claim in the collect ledger.
- **Host-only meeting authorization is enforced in SQL**, e.g. `WHERE m.meetingUid = :meetingUid AND
  m.publisherId = :requesterId`. That single atomic statement is the authorization check — never
  replace it with a load-then-compare, and never rely on the UI hiding a button.
- Every new `@Entity` needs a matching migration under `application/src/main/resources/db/migration/`
  (next free `V*` number). Local runs use `ddl-auto: update`, prod uses `ddl-auto: none` — an
  entity without a migration works locally and fails in production.
- Schema classes are opened for JPA by the `allOpen` plugin config (`@Entity`, `@MappedSuperclass`,
  `@Embeddable`) declared in each module's `build.gradle.kts`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.*'
```
Repository specs run against **H2** via `TestApplication.kt` and `src/test/resources/application.yaml`.
The convention is a spec pair per lane: `Jpa*RepositoryTest` for query/CAS semantics and
`*RepositoryImplTest` for the mapping layer. Any concurrency claim (claim CAS, `insertIgnore`,
`resetStuck`) needs a spec that exercises the losing path, not only the winning one. Schema fixtures:
`src/testFixtures/kotlin/dev/notypie/schema/` (`MeetingSchemaCreator`, `CveEventCreator`, `CveTopicCreator`).
Note that H2 will not catch MariaDB-specific native-SQL syntax — verify those against a real database.

### Common Patterns
- Native queries for atomic CAS / `INSERT ... IGNORE`; derived or JPQL queries otherwise.
- `@Modifying @Transactional` on state transitions, returning the affected row count.
- DTO projections (`dev.notypie.domain.*.dto`) cross the boundary outward; JPA schema classes never leak
  into `application` or `domain`.
- `JPAJsonConverter` (in `infrastructure/common/`) for JSON columns; `PartitionKeyUtil` for Kafka
  partition keys.

## Dependencies

### Internal
- `domain/meet/`, `domain/standup/`, `domain/command/` — entities, DTOs, `OutboundMessage`, `CommandBasicInfo`
- `infrastructure/common/` — `JPAJsonConverter`, `jsonMapper`, `PartitionKeyUtil`
- `infrastructure/configurations/JpaConfiguration.kt` — JPA/auditing wiring

### External
Spring Data JPA / Hibernate, Jackson 3 (plus `com.fasterxml.jackson.annotation` mix-in annotations in
the codec), MariaDB driver (runtime), H2 (tests).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
