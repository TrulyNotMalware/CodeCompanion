<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-02 -->

# application/service/relay

## Purpose
The transactional-outbox relay. Two mutually exclusive readers — a fixed-rate DB poller and a Debezium
CDC Kafka listener — claim `PENDING` `outbox_message` rows and hand each claim to
`SlackMessageRelayServiceImpl.dispatchClaimed`, which renews the claim, renders through one
`OutboxPayloadRenderer`, dispatches through `MessageDispatcher`, and then either writes `SUCCESS` / `FAILURE`
guarded by the claim and publishes an `OutboxUpdateEvent`, or leaves the row `IN_PROGRESS` for the recovery
sweep (rate-limited: deferred past `Retry-After`; transient: retried after the stuck threshold). `SlackMessageRelayServiceImpl` also owns the write side of the
interactive path: it turns `OutboundMessageEnqueued` into an outbox row at `BEFORE_COMMIT`.

## Key Files
| File | Description |
|------|-------------|
| `MessageProcessor.kt` | Marker `interface MessageProcessor` (no method since A4): each impl exposes its own typed entry point, so no impl narrows a shared parameter at runtime. Exists for `@ConditionalOnMissingBean(MessageProcessor::class)` bean selection |
| `MessageRelayService.kt` | `interface MessageRelayService { reserveDispatchSlots(wanted): Int; releaseDispatchSlots(count); batchPendingMessages(claims: List<OutboxClaim>); dispatchClaimed(claim: OutboxClaim) }` and `inline fun MessageRelayService.claimWithReservedSlots(wanted, claimRows: (slots) -> List<OutboxClaim>)`: reserves up to `wanted` slots, lets `claimRows` claim at most that many rows (`check`), gives the unused slots back in a `finally` (also when `claimRows` throws) and hands the claims to `batchPendingMessages`. A row is claimed only with a slot held for it, so the poller and the sweep, which run at the same time on the scheduler pool, never claim the same free room, and a claimed row never waits the stuck threshold for a full executor. `data class OutboxClaim(row: OutboxMessage, attempt: Int)` — the row plus the `attempt_count` this owner won, which is its ownership token — and the two claim helpers on `MessageOutboxRepository`: `claim(row, now)` (`claimPending` with the row's observed `attemptCount`) and `reclaim(row, olderThan, now)` (`reclaimStuck`, same guard); each returns an `OutboxClaim` with `attempt = row.attemptCount + 1` or `null` when the CAS was lost |
| `OutboxRecoveryScheduler.kt` | `@Component`, mode-independent, `@Scheduled(fixedDelay = RECOVERY_SWEEP_PERIOD_MILLIS, initialDelay = same)` (`const val RECOVERY_SWEEP_PERIOD_MILLIS = 60_000L`, also read by `OutboxHealthIndicator`) `recover()` → `recoverOnce()`. `now` comes from the injected `Clock`. `findStuckInProgress(updated_at < now - outbox.polling.stuckInProgressSeconds)` → rows with `sendCount >= outbox.polling.maxSends` (10) or `createdAt` older than `outbox.polling.giveUpAfterHours` (24) go to `abandonStuck` (FAILURE, guarded by the observed `attemptCount` and the same cutoff, so a renewed or reclaimed row is left alone), the rest to `reclaim`; plus `findStalePending` (PENDING older than the same threshold) → `claim`; both go through `claimWithReservedSlots(wanted = stuck + stale)`, reclaims first, so claims stop at the reserved slots and the remaining rows stay unclaimed for the next sweep. Abandons always run. Needed in both modes: CDC produces no second change event for a row whose status write failed, whose dispatch was rate-limited, or whose record was lost to the DLT |
| `OutboxRetentionScheduler.kt` | `@Component`, `@Scheduled(fixedDelay = 1h, initialDelay = 5min) purge()` → `purgeOnce()` deletes SUCCESS/FAILURE rows older than `slack.app.outbox.retention.days` (default 14) in batches of `retention.batch-size` (default 1000), up to 20 batches per tick until one comes back short; never touches PENDING/IN_PROGRESS |
| `PollingMessageProcessor.kt` | Not a `@Service` — bean from `configurations/ConsumerConfig.kt` `PoolingPublisherConfig` (`@Conditional(OnPollingConsumer)`). `@Scheduled(fixedRate = 5000) pollPending()` → `claimAndDispatch()`: `claimWithReservedSlots(wanted = batchSize)` → `findPendingMessages(limit = reserved slots)` → per-row `claim(row, now)`; no free slot means no read. Depends on the concrete `SlackMessageRelayServiceImpl` |
| `DebeziumLogTailingProcessor.kt` | Bean from `CdcPublisherConfig` (`@Conditional(OnCdcConsumer)`), constructed with `MessageOutboxRepository`, `MessageRelayService` and `Clock`. `@KafkaListener(topics = ["${slack.app.mode.cdc.topic}"], containerFactory = "concurrentKafkaListenerContainerFactory")` with `spring.json.value.default.type = ...relay.Envelope` on `consume(@Payload(required = false) envelope: Envelope?)`; a null payload (tombstone) is skipped. Reads `payload.after` → `toOutboxMessage()`, ignores delete events and non-`PENDING` after-images, then **re-reads the row**: only a row still `PENDING` is claimed (`claim(row = current, now)`) and passed to `relayService.dispatchClaimed` on the listener thread; `IN_PROGRESS` (another owner, or a stuck row the recovery sweep owns), `SUCCESS`, `FAILURE` and a missing row are skipped. Unparseable after-image / malformed `eventId` throw `CdcRecordParseException` so the container's error handler parks the record on `<topic>-dlt` without retrying |
| `DebeziumOutboxMessage.kt` | Jackson model of the Debezium envelope, reduced to what the processor reads (2026-10-02): `Envelope(payload)` and `Payload(op?, after?)`, both `@JsonIgnoreProperties(ignoreUnknown = true)`. Schema, `source`, `transaction` and `ts_*` are not modelled: they change with the connector, its version and the converter, and as required fields a missing one (pre-2.x records carry no `ts_us`/`ts_ns`) failed every record into the DLT. `payload` stays required, so a converter with `schemas.enable=false` (no envelope wrapper) still fails loudly |
| `OutboxPayloadRenderer.kt` | `render(row: OutboxMessage): SlackEventPayload`. `require(row.schemaVersion in OutboxSchemaVersion.SUPPORTED)` *before* decode, `Transport.valueOf(row.transport)` → `renderers[transport]` (`OutboundRenderer`), `OutboundMessageCodec.decode(row.payload)`. Bean in `SlackRequestBuilderConfiguration.outboxPayloadRenderer` mapping `Transport.SLACK` |
| `SlackMessageRelayServiceImpl.kt` | `@Service`, takes the `Clock` bean and `AppConfig` (`outbox.polling.stuckInProgressSeconds`). Dispatch slots: an `AtomicInteger` starting at `relayQueueCapacity(appConfig)` (the relay queue size, `outbox.polling.batch-size`), whatever the executor's type; `reserveDispatchSlots` takes up to `wanted` atomically, `releaseDispatchSlots` gives back, capped at the capacity. `batchPendingMessages` submits `dispatchClaimed(claim)` per claim to `relayTaskExecutor: Executor` by hand (no `@Async` — self-invocation would bypass the proxy); a claim gives its slot back when a pool thread starts it, so a running dispatch holds none and the queue never holds more claims than it has room for. A `RejectedExecutionException` (the pool's `AbortPolicy`) stops the loop, gives back the remaining slots and logs the left-over `eventId`s at WARN — those rows stay `IN_PROGRESS` with no send spent for the sweep. Nothing runs on the submitting thread, which is a shared `taskScheduler` thread. `dispatchClaimed`: parse the row `eventId` (malformed → `completeClaim(FAILURE)` at once, no render, no event); `renewClaim(eventId, attempt, now)` — also `send_count + 1`; 0 means the recovery sweep took the row over while this task waited, so nothing is sent, and a DB error there is logged and leaves the row to the sweep; render in its own `try` (throws → `MessagePublishFailedEvent`, terminal); dispatch in its own `try` (throws → logged, row stays `IN_PROGRESS`). Outcome: `isRateLimited()` → `defer` = `deferClaim(eventId, attempt, updatedAt = now + wait - stuckThreshold)` where `wait = (retryAfter() ?: 60 s) + spread`, spread = `eventId.hashCode()` mod 2 min so rows limited together come back in different sweeps, and the resulting eligibility is capped at `createdAt + giveUpAfterHours` so a long `Retry-After` (hours) is honoured but cannot park a row beyond the 24 h bound; `isTransientExhausted()` → nothing written; anything else → `completeClaim(eventId, attempt, status, now)` inside `retryService.execute(maxAttempts = 5)`, and only when that returned 1 `publishEvent(OutboxUpdateEvent)` in a separate `try` (a listener failure is logged as such). A failed or exhausted status write is caught and logged at ERROR with `eventId` / `idempotencyKey` — it never propagates, so the CDC container does not redeliver a record whose message was already sent. `@TransactionalEventListener(BEFORE_COMMIT) saveOutboxMessage(OutboundMessageEnqueued)` → `outboundMessagePort.toRow` + `save`, `maxAttempts = 3` |

## For AI Agents

### Working In This Directory
- **Only the current claim owner dispatches.** Every claim and reclaim increments `attempt_count`; the
  winner carries that number in its `OutboxClaim`. `dispatchClaimed` renews the claim with it right before
  sending (`renewClaim` also restarts the stuck clock; the sweep cannot take the row mid-send only because a
  whole dispatch is time-bounded well below `stuckInProgressSeconds` — ≤ ~50 s against 300 s, arithmetic in
  `infrastructure/.../impl/command/AGENTS.md`; lengthening a send past the threshold reopens the double send) and writes
  the terminal status with it, so a task that waited in the executor past the stuck threshold neither sends
  nor overwrites the new owner's `SUCCESS` with `FAILURE`. The CDC reader never touches an `IN_PROGRESS`
  row: stuck rows belong to `OutboxRecoveryScheduler` alone. The two readers are exclusive by
  `OnPollingConsumer` / `OnCdcConsumer` (`configurations/conditions/Conditions.kt`).
- **One clock for `updated_at`.** Every native write takes `now` from the injected `Clock` (the context's
  single `Clock` bean), the same clock the sweep's cutoffs, the retention purge and `OutboxHealthIndicator`
  use, and the one `@CreationTimestamp`/`@UpdateTimestamp` follow (JVM time). Never reintroduce
  `CURRENT_TIMESTAMP`: the DB session zone differs from the JVM zone in the shipped manifests.
- **The row `eventId` is the identity.** Both readers parse it up front and key every `OutboxUpdateEvent`
  on it; the payload `eventId` the renderer mints is throwaway. A malformed row id is deterministic: the
  polling/sweep path completes the claim as `FAILURE` at once, the CDC path sends the record to the DLT.
- **Render is not retried; dispatch is.** A codec / schema failure surfaces as `MessagePublishFailedEvent`
  immediately; quick retries live inside `MessageDispatcher`, slow ones in the recovery sweep. The schema guard refuses any `schemaVersion` not in
  `OutboxSchemaVersion.SUPPORTED` — when adding a version, extend `SUPPORTED` and keep `decode` able to
  read the old shape, otherwise a rolling deploy strands rows.
- **`saveOutboxMessage` is `BEFORE_COMMIT`.** The row commits atomically with the command's own writes,
  which is why `SlackOutboundStager` must be called inside a `@Transactional` handler
  (`SlackInteractionHandlerImpl`, `SlackMentionEventHandlerImpl`, the slash services). With no active
  transaction the event is simply not delivered (`fallbackExecution` defaults to false) and nothing
  reaches the outbox.
- **Three dispatch outcomes, one budget in real sends.** Done (success or permanent failure) is written
  and published. Rate-limited and transient-exhausted rows, and dispatches that threw, stay `IN_PROGRESS`
  for the sweep. `attempt_count` is only the ownership token; the abandon budget is `send_count`, which
  `renewClaim` raises right before a send and `deferClaim` takes back for a rate limit. So 429s and claims
  taken over in the executor queue never abandon a row; `outbox.polling.maxSends` transient sends do, and
  the 24 h `created_at` bound stops everything. A resend can post twice in Slack (no idempotency key
  there), which beats a row that never resolves.
- **An outcome event is published only by the owner that recorded it.** `completeClaim` returning 0 means
  another owner has the row and will publish its own outcome.
- **`MessagePublishSuccessEvent` has a downstream consumer:** `service/standup/StandupSummaryService`
  swaps its `outbox:<eventId>` marker for `messageTs`. Keep `messageTs` populated on success. It is the
  only listener of `OutboxUpdateEvent`s.
- The `Envelope` FQN is hardcoded in the `@KafkaListener` properties; moving or renaming the class
  breaks CDC deserialization at runtime with no compile error. `relayTaskExecutor` is the dedicated bounded
  `@Qualifier("relayTaskExecutor")` bean in `configurations/AsyncConfig.kt`; `Error`s deliberately propagate to
  its uncaught handler.
- **Never run relay work on a scheduler thread.** The poller and the sweep share `taskScheduler` with every
  other `@Scheduled` job, so a dispatch there stalls reminders, standups and the CVE lane. Claim through
  `claimWithReservedSlots` and let a rejected claim go back to the sweep; do not reintroduce `CallerRunsPolicy`.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.relay.*'
```
Specs under `application/src/test/kotlin/dev/notypie/application/service/relay/` (see that directory's
`AGENTS.md`). Fixtures from `dev.notypie.application.outbox` (application testFixtures):
`createPollingProcessorFixture(clock)` (its default relay mock grants every reservation), `createRelayService(outboxRepository, ...)` (real service, real
`RetryService`, inline `Executor` unless one is passed, fixed clock; its slots are `appConfig`'s batch size), `stubClaimLifecycle(renewed, completed,
deferred)`, `createOutboxRow(attemptCount, sendCount)`, `createFixedUtcClock`, `DEFAULT_TEST_NOW`, and for the
H2 scenario `createOutboxJpaContext`, `MutableClock`, `ScriptedMessageDispatcher`, `QueuedExecutor`; from `dev.notypie.application.service.relay`: `createCdcEnvelope`,
`createOutboxAfterImage`, `createCdcConsumerRecord`. Codec / schema / SQL behaviour is covered by
`infrastructure/src/test/kotlin/dev/notypie/repository/outbox/`.

### Common Patterns
- Reader beans are plain classes wired in `ConsumerConfig.kt`, not component-scanned — so a profile
  or condition decides which one exists.
- `runCatching { UUID.fromString(...) }.getOrElse { log; return }` for row-id parsing.
- `retryService.execute(action = {...}, maxAttempts = n)` around the terminal status write, which runs outside any caller transaction. Never around `saveOutboxMessage`'s `save`: it runs inside the command's transaction, a failure there already marks it rollback-only, and the INSERT only executes at the commit flush anyway.
- Error logs carry `eventId` and `idempotencyKey` — keep that shape for outbox debugging.

## Dependencies

### Internal
- `infrastructure/repository/outbox/` — `MessageOutboxRepository` (`findPendingMessages`, `claimPending`,
  `findStuckInProgress`, `findStalePending`, `reclaimStuck`, `abandonStuck`, `renewClaim`, `deferClaim`,
  `completeClaim`, `deleteTerminalOlderThan`, `findById`, `save`), `OutboundMessagePort`, `OutboundMessageCodec`,
  `Transport`, `OutboxSchemaVersion`, `schema/OutboxMessage` (`attemptCount`, `sendCount`, `toOutboxMessage`),
  `schema/MessageStatus`, `dto/OutboxUpdateEvent` + `MessagePublishFailedEvent` /
  `MessagePublishSuccessEvent` + `toOutboxUpdateEvent`
- `infrastructure/impl/command/event/` — `MessageDispatcher`, `SlackEventPayload`, `OutboundMessageEnqueued`
- `infrastructure/impl/command/` — `OutboundRenderer`, `isRateLimited()` / `isTransientExhausted()` /
  `retryAfter()` on the dispatcher's `CommandOutput`; `infrastructure/impl/retry/RetryService`
- `application/configurations/` — `ConsumerConfig.kt`, `SlackRequestBuilderConfiguration.kt`,
  `AsyncConfig.kt`, `AppConfig.outbox.polling.{batchSize, stuckInProgressSeconds, giveUpAfterHours, maxSends}`,
  the `Clock` bean from `security/SlackRequestVerificationConfiguration`
- `application/service/standup/StandupSummaryService` — consumer of `MessagePublishSuccessEvent`

### External
Spring scheduling, Spring Kafka (`@KafkaListener`), Spring transaction events, Jackson, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
