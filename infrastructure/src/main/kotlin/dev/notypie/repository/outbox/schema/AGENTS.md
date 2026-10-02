<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-02 -->

# infrastructure/repository/outbox/schema

## Purpose
The `outbox_message` entity plus the two value types that govern its lifecycle: the status enum whose names
are the literals in the native CAS statements, and the payload schema-version gate the relay checks before
decoding.

## Key Files
| File | Description |
|------|-------------|
| `OutboxMessage.kt` | `@Entity @Table(name = "outbox_message", indexes = [idx_outbox_idempotency_key])`. PK `event_id: String`; `idempotency_key`, `publisher_id`, `transport` (`String`, default `SLACK`), `payload` (`TEXT`, codec-encoded envelope), `created_at` (`@CreationTimestamp`, not updatable), `updated_at?` (`@UpdateTimestamp`), `schema_version` (`INT NOT NULL DEFAULT 2`, default `OutboxSchemaVersion.CURRENT`), `attempt_count` (`INT NOT NULL DEFAULT 0`, `updatable = false`, default `0`; raised only by the native claim statements, V20), `send_count` (`INT NOT NULL DEFAULT 0`, `updatable = false`, default `0`; raised by `renewClaim`, lowered by `deferClaim`, V22). Body: `@Version var version: Long` and `var status: String = PENDING.name`, both `protected set`; `updateMessageStatus(MessageStatus)`. Also `MutableMap<String, Any>.toOutboxMessage()` for Debezium rows, converting epoch-micro `Long` timestamps to `LocalDateTime` before `jsonMapper.convertValue`. Implements `Persistable<String>`: a `@Transient` flag is true for a freshly built row and cleared by `@PostPersist`/`@PostLoad`, so `save()` of a new row is a plain `persist` (an application-assigned id with a primitive `@Version` would otherwise make Spring Data `merge`, issuing a SELECT before every INSERT on the busiest write path). Only new rows are ever saved; status changes go through the native CAS statements |
| `MessageStatus.kt` | `enum MessageStatus { INIT, FAILURE, SUCCESS, PENDING, IN_PROGRESS }` |
| `OutboxSchemaVersion.kt` | `object OutboxSchemaVersion { const V2 = 2; const CURRENT = V2; val SUPPORTED: Set<Int> = setOf(V2) }` |

## For AI Agents

### Working In This Directory
- **`status` and `transport` are `String` columns, not `@Enumerated`.** The `transport` comment records why:
  Debezium CDC delivered enum-typed columns as `null`. The native statements in `MessageOutboxRepository`
  compare against the literal names, so renaming a `MessageStatus` constant is a data migration (and
  `NativeQueryStatusLiteralTest` fails until the SQL is updated too). `status` is
  mapped `nullable = false` like the property; an existing database column keeps its own nullability.
- **The `@JsonProperty("event_id")`-style annotations exist for the Debezium path**: `toOutboxMessage()`
  maps a CDC row (snake_case keys, epoch `Long` dates) with `dev.notypie.common.jsonMapper`. Debezium writes a
  `DATETIME` as epoch time read as UTC, in millis for `DATETIME(0-3)` and micros for `DATETIME(4-6)`;
  `debeziumDateTime()` decodes it in UTC (not the JVM zone, which shifted it by nine hours in `Asia/Seoul`) and tells the
  units apart by magnitude (2026-10-02). Keep the
  JSON property names aligned with the column names or log tailing breaks while the JPA path keeps working.
- **`version` is a JPA optimistic lock**, unrelated to `schemaVersion`. The native CAS statements bypass it,
  and since status writes moved to `completeClaim` no code saves an existing row through JPA.
- **`attemptCount` and `sendCount` are read-only to JPA** (`updatable = false`): only `claimPending` /
  `reclaimStuck` change the first, only `renewClaim` / `deferClaim` the second. A Debezium after-image without
  either column (written before V20 / V22) maps to the default `0`.
- **Bumping the payload shape**: add `V3`, set `CURRENT = V3`, and add `V3` to `SUPPORTED` in the same
  change; remove `V2` from `SUPPORTED` only after the outbox is guaranteed drained. The relay refuses to
  decode a row whose version is outside `SUPPORTED`, leaving it stuck (visible to the health indicator)
  rather than sending a malformed request. V1 (pre-rendered Slack body across payload / metadata / type
  columns) is unsupported; see `V11__outbox_transport_neutral_envelope.sql`.
- Migrations: `V1__outbox_pk_event_id.sql`, `V11__outbox_transport_neutral_envelope.sql`,
  `V19__add_outbox_status_indexes.sql`, `V20__add_outbox_attempt_count.sql`, `V22__add_outbox_send_count.sql`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.outbox.schema.OutboxMessageTest'
```
`OutboxMessageTest` (BehaviorSpec, no Spring) checks `CodecOutboundMessagePort.toRow` output (identity
columns, `schemaVersion == CURRENT`, `status == PENDING`, fresh `eventId` per call, payload round-trip) and
`updateMessageStatus`, and `toOutboxMessage()` with a micros `created_at` and a millis `updated_at` while the JVM default
zone is set to `Asia/Seoul` (both decode to the stored wall-clock value). The H2 mapping is exercised by
`../MessageOutboxRepositoryTest`; `:application`'s `DebeziumLogTailingProcessorTest` runs the converter on full envelopes.

### Common Patterns
- `@field:` targeted annotations on constructor `val`s; mutable state in the class body with `protected set`.
- Explicit snake_case `@Column(name = ...)` on every column, mirrored by `@JsonProperty`.

## Dependencies

### Internal
- `repository/outbox/Transport`, `common/JsonMapper.kt` (`jsonMapper`)
- `application/src/main/resources/db/migration/V1__*`, `V11__*`, `V19__*`, `V20__*`

### External
Jakarta Persistence, Hibernate `@CreationTimestamp` / `@UpdateTimestamp`, Jackson annotations, `kotlin-logging`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
