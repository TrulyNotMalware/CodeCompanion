<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# infrastructure/repository/outbox

## Purpose
The transactional outbox. A domain write and its outbound side effect commit together because the caller
inserts an `OutboxMessage` row in the same transaction as the domain change; a poller later claims PENDING
rows with an atomic CAS and the relay decodes the row's transport-neutral payload back into an
`OutboundEnvelope` to render it for the wire. This package holds the row builder, the codec, the transport
tag and the Spring Data repository carrying the claim / recovery / health queries.

## Key Files
| File | Description |
|------|-------------|
| `MessageOutboxRepository.kt` | `JpaRepository<OutboxMessage, String>` (PK is `event_id`), all native SQL. `findPendingMessages(limit, offset)` (PENDING, oldest first); `claimPending(eventIds): Int` — `UPDATE ... SET status = 'IN_PROGRESS', updated_at = CURRENT_TIMESTAMP WHERE event_id IN (:eventIds) AND status = 'PENDING'`; `findStuckInProgress(olderThan, limit)`; health scalars `findOldestPendingCreatedAt()`, `countPending()`, `countPendingOlderThan(threshold)`, `countInProgress()`, `countInProgressOlderThan(threshold)`, `findOldestInProgressUpdatedAt()` |
| `OutboundEnvelope.kt` | `data class OutboundEnvelope(message: OutboundMessage, basicInfo: CommandBasicInfo)` — the unit that is codec-encoded into the row |
| `OutboundMessageCodec.kt` | `object OutboundMessageCodec { encode(envelope): String; decode(json): OutboundEnvelope }` over a private Jackson 3 `JsonMapper`; every `JacksonException` is wrapped in `OutboundMessageCodecException`. Three private mix-ins: `OutboundMessageMixin` / `MessageContentMixin` (`@JsonTypeInfo(NAME, property = "@type")` + `@JsonSubTypes`) and `TimeScheduleInfoMixin` (`@JsonIgnoreProperties("timeFormatter")`) |
| `OutboundMessagePort.kt` | `interface OutboundMessagePort { toRow(message, basicInfo, transport = Transport.SLACK): OutboxMessage }` and `CodecOutboundMessagePort`, which mints a random `eventId`, copies `idempotencyKey` / `publisherId` from `basicInfo`, encodes the envelope and stamps `createdAt = now()` |
| `Transport.kt` | `enum Transport { SLACK }` — persisted by `name` on the row so the relay can pick a renderer |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `schema/` | `OutboxMessage` entity, `MessageStatus`, `OutboxSchemaVersion` (see `schema/AGENTS.md`) |
| `dto/` | `OutboxUpdateEvent` hierarchy and `CommandOutput.toOutboxUpdateEvent` (see `dto/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **`claimPending` returns the count, not the rows.** The `status = 'PENDING'` predicate is the concurrency
  guarantee at the row level, but the caller (`PollingMessageProcessor.claimAndDispatch`) only learns *how
  many* transitioned and dispatches `candidates.take(claimedCount)` — it cannot tell *which* rows it won.
  With two pollers racing over the same candidates, one may dispatch a row the other claimed while its own
  claimed row sits `IN_PROGRESS` until stuck recovery resends it. This is accepted on the current
  single-instance deployment; a multi-poller deployment needs a claim that returns the winning ids
  (e.g. claim token + re-select) before it is safe.
- **`updated_at` is touched inside `claimPending` on purpose.** `findStuckInProgress` and
  `countInProgressOlderThan` age IN_PROGRESS rows from claim time; a native bulk UPDATE bypasses Hibernate's
  `@UpdateTimestamp`, so removing the explicit `updated_at = CURRENT_TIMESTAMP` silently disables stuck-row
  recovery.
- **The subtype registries in `OutboundMessageCodec` are deliberately incomplete.** `OpenModal` and
  `DirectMessage` are not outbox-bound; leaving them unregistered makes a mis-staged message fail at
  `encode` with an unresolved type id instead of reaching the relay. Register a new outbox-bound
  `OutboundMessage` / `MessageContent` subtype here and add a round-trip case to
  `OutboundMessageCodecTest`; the domain types stay annotation-free.
- **The codec's mapper is private and configured once**: `findAndAddModules()`, Kotlin module with
  `UseJavaDurationConversion` + `KotlinPropertyNameAsImplicitName`, `ORDER_MAP_ENTRIES_BY_KEYS`, ISO dates
  (`WRITE_DATES_AS_TIMESTAMPS` disabled). Do not swap in `dev.notypie.common.jsonMapper` — the mix-ins must
  not leak into the general-purpose mapper, and the payload format must not drift with it.
- **`CodecOutboundMessagePort` persists nothing.** Callers save the returned row through their own
  repository inside their own `@Transactional` so the outbox insert shares the domain transaction.
- `status` and `transport` on the row are plain `String` columns written via `updateMessageStatus(MessageStatus)`
  and the native statements; the literals in the SQL must equal `MessageStatus.name`.
- Consumers in `:application`: `PollingMessageProcessor`, `SlackMessageRelayServiceImpl`,
  `OutboxPayloadRenderer` (codec + `OutboxSchemaVersion`), `DebeziumLogTailingProcessor`,
  `OutboxHealthIndicator`, `OpsStatusService`, and every scheduler / dispatcher that enqueues through
  `OutboundMessagePort` (`StandupSchedulingService`, `StandupSummaryService`, `MeetingReminderSchedulingService`,
  `DailyAgendaSchedulingService`, `CveNotificationDispatcher`).

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.outbox.*'
```
`OutboundMessageCodecTest` (StringSpec) covers every registered subtype round-trip, the `Schedule`
field-wise comparison (`DateTimeFormatter` has no `equals`) and the fail-fast cases; `schema/OutboxMessageTest`
covers `toRow` and `updateMessageStatus`. **Nothing exercises `MessageOutboxRepository`'s SQL on H2** — the
claim CAS, `findStuckInProgress` and the health counters are only covered indirectly from `:application`.
A `@DataJpaTest` that claims the same ids twice and asserts `1` then `0` is the missing spec.

### Common Patterns
- Native queries with string status literals (`'PENDING'`, `'IN_PROGRESS'`).
- `@Modifying @Transactional` on the CAS, returning `Int`.
- Row identity: `eventId` (random UUID string) is the PK; `idempotencyKey` is indexed but not unique, so
  one command can legitimately produce several rows.

## Dependencies

### Internal
- `domain/command/outbound` — `OutboundMessage`, `MessageContent`; `domain/command/dto` — `CommandBasicInfo`,
  `modals/TimeScheduleInfo`
- `repository/outbox/schema`, `repository/outbox/dto`

### External
Spring Data JPA, Jackson 3 (`tools.jackson.*`) plus `com.fasterxml.jackson.annotation` for the mix-in
annotations.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
