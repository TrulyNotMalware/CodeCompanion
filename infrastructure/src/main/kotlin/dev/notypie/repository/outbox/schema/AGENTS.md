<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# infrastructure/repository/outbox/schema

## Purpose
The `outbox_message` entity plus the two value types that govern its lifecycle: the status enum whose names
are the literals in the native CAS statements, and the payload schema-version gate the relay checks before
decoding.

## Key Files
| File | Description |
|------|-------------|
| `OutboxMessage.kt` | `@Entity @Table(name = "outbox_message", indexes = [idx_outbox_idempotency_key])`. PK `event_id: String`; `idempotency_key`, `publisher_id`, `transport` (`String`, default `SLACK`), `payload` (`TEXT`, codec-encoded envelope), `created_at` (`@CreationTimestamp`, not updatable), `updated_at?` (`@UpdateTimestamp`), `schema_version` (`INT NOT NULL DEFAULT 2`, default `OutboxSchemaVersion.CURRENT`). Body: `@Version var version: Long` and `var status: String = PENDING.name`, both `protected set`; `updateMessageStatus(MessageStatus)`. Also `MutableMap<String, Any>.toOutboxMessage()` for Debezium rows, converting epoch-micro `Long` timestamps to `LocalDateTime` before `jsonMapper.convertValue` |
| `MessageStatus.kt` | `enum MessageStatus { INIT, FAILURE, SUCCESS, PENDING, IN_PROGRESS }` |
| `OutboxSchemaVersion.kt` | `object OutboxSchemaVersion { const V2 = 2; const CURRENT = V2; val SUPPORTED: Set<Int> = setOf(V2) }` |

## For AI Agents

### Working In This Directory
- **`status` and `transport` are `String` columns, not `@Enumerated`.** The `transport` comment records why:
  Debezium CDC delivered enum-typed columns as `null`. The native statements in `MessageOutboxRepository`
  compare against the literal names, so renaming a `MessageStatus` constant is a data migration.
- **The `@JsonProperty("event_id")`-style annotations exist for the Debezium path**: `toOutboxMessage()`
  maps a CDC row (snake_case keys, micro-epoch `Long` dates) with `dev.notypie.common.jsonMapper`. Keep the
  JSON property names aligned with the column names or log tailing breaks while the JPA path keeps working.
- **`version` is a JPA optimistic lock**, unrelated to `schemaVersion`. The native CAS statements bypass it;
  it only guards entity-level `save` paths.
- **Bumping the payload shape**: add `V3`, set `CURRENT = V3`, and add `V3` to `SUPPORTED` in the same
  change; remove `V2` from `SUPPORTED` only after the outbox is guaranteed drained. The relay refuses to
  decode a row whose version is outside `SUPPORTED`, leaving it stuck (visible to the health indicator)
  rather than sending a malformed request. V1 (pre-rendered Slack body across payload / metadata / type
  columns) is unsupported; see `V11__outbox_transport_neutral_envelope.sql`.
- Migrations: `V1__outbox_pk_event_id.sql`, `V11__outbox_transport_neutral_envelope.sql`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.outbox.schema.OutboxMessageTest'
```
`OutboxMessageTest` (BehaviorSpec, no Spring) checks `CodecOutboundMessagePort.toRow` output (identity
columns, `schemaVersion == CURRENT`, `status == PENDING`, fresh `eventId` per call, payload round-trip) and
`updateMessageStatus`. `toOutboxMessage()` and the H2 mapping of this entity are not covered anywhere.

### Common Patterns
- `@field:` targeted annotations on constructor `val`s; mutable state in the class body with `protected set`.
- Explicit snake_case `@Column(name = ...)` on every column, mirrored by `@JsonProperty`.

## Dependencies

### Internal
- `repository/outbox/Transport`, `common/JsonMapper.kt` (`jsonMapper`)
- `application/src/main/resources/db/migration/V1__*`, `V11__*`

### External
Jakarta Persistence, Hibernate `@CreationTimestamp` / `@UpdateTimestamp`, Jackson annotations, `kotlin-logging`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
