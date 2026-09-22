<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-09-22 -->

# application/service/relay

## Purpose
The transactional-outbox relay. Two mutually exclusive readers — a fixed-rate DB poller and a Debezium
CDC Kafka listener — pick up `PENDING` `message_outbox` rows, render them through one
`OutboxPayloadRenderer`, dispatch through `MessageDispatcher`, and publish an `OutboxUpdateEvent` that
moves the row to `SUCCESS` / `FAILED`. `SlackMessageRelayServiceImpl` also owns the write side of the
interactive path: it turns `OutboundMessageEnqueued` into an outbox row at `BEFORE_COMMIT`.

## Key Files
| File | Description |
|------|-------------|
| `MessageProcessor.kt` | Marker `interface MessageProcessor` (no method since A4): each impl exposes its own typed entry point, so no impl narrows a shared parameter at runtime. Exists for `@ConditionalOnMissingBean(MessageProcessor::class)` bean selection |
| `MessageRelayService.kt` | `interface MessageRelayService { batchPendingMessages(List<OutboxMessage>) }` |
| `OutboxRecoveryScheduler.kt` | `@Component`, mode-independent, `@Scheduled(fixedDelay = 60s, initialDelay = 60s) recover()` → `recoverOnce()`: `findStuckInProgress` → rows whose `created_at` is older than `outbox.polling.giveUpAfterHours` (24) are `abandonStuck` → FAILURE (bounded retry loop; `created_at` is the one timestamp the sweep never resets), the rest per-row `reclaimStuck` CAS; plus `findStalePending` (PENDING older than `outbox.polling.stuckInProgressSeconds`) → per-row `claimPending` CAS; then `MessageRelayService.batchPendingMessages` for the rows it won. A dispatch that ends in Slack's rate limit (`CommandOutput.isRateLimited()`) publishes no status update on purpose so this sweep retries it minutes later. Exists because CDC mode now claims rows (`IN_PROGRESS`) and never gets a second change event for a row whose status update failed or whose record went to the DLT |
| `OutboxRetentionScheduler.kt` | `@Component`, `@Scheduled(fixedDelay = 1h, initialDelay = 5min) purge()` → `purgeOnce()` deletes SUCCESS/FAILURE rows older than `slack.app.outbox.retention.days` (default 14) in batches of `retention.batch-size` (default 1000), up to 20 batches per tick until one comes back short; never touches PENDING/IN_PROGRESS |
| `PollingMessageProcessor.kt` | Not a `@Service` — bean from `configurations/ConsumerConfig.kt` `PoolingPublisherConfig` (`@Conditional(OnPollingConsumer)`). `@Scheduled(fixedRate = 5000) pollPending()` → `claimAndDispatch()` (`findPendingMessages(limit = batchSize)` → per-row `claimPending(listOf(id))` CAS → dispatch exactly the claimed rows). Stuck/stale recovery is not here any more — see `OutboxRecoveryScheduler`. Depends on the concrete `SlackMessageRelayServiceImpl` |
| `DebeziumLogTailingProcessor.kt` | Bean from `CdcPublisherConfig` (`@Conditional(OnCdcConsumer)`). `@KafkaListener(topics = ["${slack.app.mode.cdc.topic}"], containerFactory = "concurrentKafkaListenerContainerFactory")` with `spring.json.value.default.type = ...relay.Envelope` on `consume(@Payload(required = false) envelope: Envelope?)` — typed, no parameter cast since A4; a null payload (tombstone) is skipped. Reads `payload.after` → `toOutboxMessage()`, ignores delete events and non-`PENDING` after-images, then **re-reads the row** (`MessageOutboxRepository.findById`): `PENDING` → `claimPending` CAS, `IN_PROGRESS` → re-dispatch, else skip as a redelivery. Render + dispatch, publish the `OutboxUpdateEvent` (or `MessagePublishFailedEvent` on exception). Unparseable after-image / malformed `eventId` throw `CdcRecordParseException` so the container's `DefaultErrorHandler` parks the record on the DLT |
| `DebeziumOutboxMessage.kt` | Jackson model of the Debezium envelope: `Envelope(schema, payload)`, `Schema`, `Field`, `SubField`, `Parameters`, `Payload(before, after, source, transaction, op, ts_ms/ts_us/ts_ns)`, `Source`, `Transaction` |
| `OutboxPayloadRenderer.kt` | `render(row: OutboxMessage): SlackEventPayload`. `require(row.schemaVersion in OutboxSchemaVersion.SUPPORTED)` *before* decode, `Transport.valueOf(row.transport)` → `renderers[transport]` (`OutboundRenderer`), `OutboundMessageCodec.decode(row.payload)`. Bean in `SlackRequestBuilderConfiguration.outboxPayloadRenderer` mapping `Transport.SLACK` |
| `SlackMessageRelayServiceImpl.kt` | `@Service`. `batchPendingMessages` submits each row to `relayTaskExecutor: Executor` by hand (no `@Async` — self-invocation would bypass the proxy). `internal batchPendingMessagesAsync(row)`: parse the row `eventId`, render + dispatch inside one `try`, `catch (Exception)` → `MessagePublishFailedEvent`, publish the `OutboxUpdateEvent`. `@EventListener updateOutboxMessageStatus(OutboxUpdateEvent)` → `retryService.execute(maxAttempts = 5) { updateMessage }` relying on `OutboxMessage`'s `@Version`. `@TransactionalEventListener(BEFORE_COMMIT) saveOutboxMessage(OutboundMessageEnqueued)` → `outboundMessagePort.toRow` + `save`, `maxAttempts = 3` |

## For AI Agents

### Working In This Directory
- **Never dispatch a row you did not claim.** Both readers claim per row (`claimPending(listOf(id)) == 1`)
  and dispatch only the winners; the CDC reader additionally re-reads the row's current status first
  (see its row above). The two readers are exclusive
  by `OnPollingConsumer` / `OnCdcConsumer` (`configurations/conditions/Conditions.kt`, the configured
  publisher mode `POOLING` vs `CDC`). Running both would double-dispatch.
- **The row `eventId` is the identity.** Both readers parse it up front and key every `OutboxUpdateEvent`
  on it; the payload `eventId` the renderer mints is throwaway. A malformed row id is logged and skipped,
  never dispatched.
- **Render is not retried; dispatch is.** A codec / schema failure surfaces as `MessagePublishFailedEvent`
  immediately; retries live inside `MessageDispatcher`. The schema guard refuses any `schemaVersion` not in
  `OutboxSchemaVersion.SUPPORTED` — when adding a version, extend `SUPPORTED` and keep `decode` able to
  read the old shape, otherwise a rolling deploy strands rows.
- **`saveOutboxMessage` is `BEFORE_COMMIT`.** The row commits atomically with the command's own writes,
  which is why `SlackOutboundStager` must be called inside a `@Transactional` handler
  (`SlackInteractionHandlerImpl`, `SlackMentionEventHandlerImpl`, the slash services). With no active
  transaction the event is simply not delivered (`fallbackExecution` defaults to false) and nothing
  reaches the outbox.
- **Stuck `IN_PROGRESS` rows are re-dispatched, not reset.** Dispatch is idempotent per `event_id`, so a
  resend beats a row that never resolves. The threshold is `outbox.polling.stuckInProgressSeconds`.
- **`MessagePublishSuccessEvent` has a downstream consumer:** `service/standup/StandupSummaryService`
  swaps its `outbox:<eventId>` marker for `messageTs`. Keep `messageTs` populated on success.
- The `Envelope` FQN is hardcoded in the `@KafkaListener` properties; moving or renaming the class
  breaks CDC deserialization at runtime with no compile error. `relayTaskExecutor` is the dedicated bounded
  `@Qualifier("relayTaskExecutor")` bean in `configurations/AsyncConfig.kt` (before 2026-09-22 the name matched
  nothing and the `@Primary` global pool was injected); `Error`s deliberately propagate to its uncaught
  handler.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.relay.*'
```
Specs under `application/src/test/kotlin/dev/notypie/application/service/relay/`:
`PollingMessageProcessorTest`, `SlackMessageRelayServiceImplTest`, `OutboxPayloadRendererTest`. Fixtures
from `dev.notypie.application.outbox` (application testFixtures): `createPollingProcessorFixture(clock)`
returning `(outboxRepository, relayService, processor)`, `createOutboxRow`, `createFixedUtcClock`; plus
`createCommandBasicInfo`. Use a direct `Executor { it.run() }` when testing the relay service so the
async branch runs inline. `DebeziumLogTailingProcessorTest` drives `consume()` directly with `createCdcEnvelope` / `createOutboxAfterImage` from `dev.notypie.application.service.relay` (testFixtures) and a mocked `MessageOutboxRepository`;
codec / schema behaviour is covered by `infrastructure/src/test/kotlin/dev/notypie/repository/outbox/`.

### Common Patterns
- Reader beans are plain classes wired in `ConsumerConfig.kt`, not component-scanned — so a profile
  or condition decides which one exists.
- `runCatching { UUID.fromString(...) }.getOrElse { log; return }` for row-id parsing.
- `retryService.execute(action = {...}, maxAttempts = n)` around every repository write.
- Error logs carry `eventId` and `idempotencyKey` — keep that shape for outbox debugging.

## Dependencies

### Internal
- `infrastructure/repository/outbox/` — `MessageOutboxRepository` (`findPendingMessages`, `claimPending`,
  `findStuckInProgress`, `findById`, `save`), `OutboundMessagePort`, `OutboundMessageCodec`, `Transport`,
  `OutboxSchemaVersion`, `schema/OutboxMessage` (`@Version`, `updateMessageStatus`, `toOutboxMessage`),
  `schema/MessageStatus`, `dto/OutboxUpdateEvent` + `MessagePublishFailedEvent` /
  `MessagePublishSuccessEvent` + `toOutboxUpdateEvent`
- `infrastructure/impl/command/event/` — `MessageDispatcher`, `SlackEventPayload`, `OutboundMessageEnqueued`
- `infrastructure/impl/command/OutboundRenderer`, `infrastructure/impl/retry/RetryService`
- `application/configurations/` — `ConsumerConfig.kt`, `SlackRequestBuilderConfiguration.kt`,
  `AsyncConfig.kt`, `AppConfig.outbox.polling.{batchSize, stuckInProgressSeconds}`
- `application/service/standup/StandupSummaryService` — consumer of `MessagePublishSuccessEvent`

### External
Spring scheduling, Spring Kafka (`@KafkaListener`), Spring transaction events, Jackson, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
