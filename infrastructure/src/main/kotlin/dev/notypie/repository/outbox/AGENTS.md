<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-09-28 -->

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
| `MessageOutboxRepository.kt` | `JpaRepository<OutboxMessage, String>` (PK is `event_id`), all native SQL; every write binds `now` from the caller. `findPendingMessages(limit)` (PENDING, oldest `created_at` first); `claimPending(eventId, attemptCount, now): Int` — `PENDING` → `IN_PROGRESS`, `attempt_count + 1`, `updated_at = :now`, guarded by `status = 'PENDING' AND attempt_count = :attemptCount`; `findStuckInProgress(olderThan, limit)`; `findStalePending(olderThan, limit)`; `reclaimStuck(eventId, attemptCount, olderThan, now): Int` — `attempt_count + 1`, `updated_at = :now` while the row is still `IN_PROGRESS` on the observed attempt and older than the cutoff; `abandonStuck(eventId, attemptCount, olderThan, now): Int` — `IN_PROGRESS` → `FAILURE` under the same observed-state guard; `renewClaim(eventId, attemptCount, now): Int` — the owner's lease refresh right before sending, which also adds 1 to `send_count`; `deferClaim(eventId, attemptCount, updatedAt): Int` — the owner's rate-limit deferral: takes that send back (`GREATEST(send_count - 1, 0)`) and sets `updated_at` to the caller's value so the stuck guard waits for `Retry-After`; `completeClaim(eventId, attemptCount, status, now): Int` — the owner's terminal write, `IN_PROGRESS` → `status` only on its own attempt; `deleteTerminalOlderThan(olderThan, limit): Int` — retention purge of SUCCESS/FAILURE rows (`DELETE ... LIMIT`, MariaDB and H2); health scalars `findOldestPendingCreatedAt()`, `countPending()`, `countPendingOlderThan(threshold)`, `countInProgress()`, `countInProgressOlderThan(threshold)`, `findOldestInProgressUpdatedAt()`, `countInProgressWithSendsAtLeast(sends)` |
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
- **`attempt_count` is the ownership token.** `claimPending` and `reclaimStuck` are the only statements
  that raise it, each guarded by the value the caller read, so the winner knows its attempt is
  `observed + 1` (the `claim` / `reclaim` helpers in `application/service/relay/MessageRelayService.kt`).
  `renewClaim`, `deferClaim`, `completeClaim` and `abandonStuck` all require that exact attempt: a worker whose
  row was reclaimed can neither send, defer nor overwrite the new owner's result, and the sweep cannot abandon
  a row a fresh owner just renewed. Claims are single-id; a bulk claim could not say which rows were won.
- **`send_count` is the retry budget, `attempt_count` is not.** Only `renewClaim` (the step right before a
  send) raises it and only `deferClaim` (a rate limit) lowers it, so claims that were taken over in the
  relay queue and sends Slack rate-limited never count. The recovery sweep abandons on `send_count`, and
  the health probe's retrying count reads it (V22).
- **`updated_at` is written from the caller's `now`, never `CURRENT_TIMESTAMP`.** The cutoffs the sweep,
  the purge and the health probe compare against are computed from the application `Clock` (JVM zone), and
  `@CreationTimestamp`/`@UpdateTimestamp` use the JVM clock too; `CURRENT_TIMESTAMP` is evaluated in the DB
  session zone, which is UTC in the shipped MariaDB manifests while the app runs in Asia/Seoul. Mixing the
  two made every fresh claim look nine hours stuck. The stamp still matters: stuck detection and the
  health ages read it, and native UPDATEs bypass `@UpdateTimestamp`.
- **`completeClaim` replaced the JPA read-modify-write status update.** Nothing saves an existing
  `OutboxMessage` through JPA any more; `@Version` stays on the entity but no current writer relies on it.
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
  `OutboxRecoveryScheduler`, `OutboxRetentionScheduler`, `OutboxHealthIndicator`, `OpsStatusService`, and every scheduler / dispatcher that enqueues through
  `OutboundMessagePort` (`StandupSchedulingService`, `StandupSummaryService`, `MeetingReminderSchedulingService`,
  `DailyAgendaSchedulingService`, `CveNotificationDispatcher`).

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.outbox.*'
```
`OutboundMessageCodecTest` (StringSpec) covers every registered subtype round-trip, the `Schedule`
field-wise comparison (`DateTimeFormatter` has no `equals`) and the fail-fast cases; `schema/OutboxMessageTest`
covers `toRow` and `updateMessageStatus`; `MessageOutboxRepositoryTest` (`@DataJpaTest`, H2) covers the
native statements, including that `updated_at` is the caller's `now` and not the database clock. H2 runs in
the test JVM, so it cannot reproduce a DB session zone that differs from the JVM; the caller-`now` design is
what removes that dependency.

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

- Indexes on `outbox_message`: `idx_outbox_idempotency_key`, `idx_outbox_status_created_at`, `idx_outbox_status_updated_at` (V19) — every hot query filters on `status`. `attempt_count` (V20) and `send_count` (V22) are unindexed; the retrying health count filters on `status` first.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
