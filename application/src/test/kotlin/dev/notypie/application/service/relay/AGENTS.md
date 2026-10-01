<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-01 -->

# test/kotlin/dev/notypie/application/service/relay

## Purpose
Specs for the outbox relay: the CDC and polling readers, the recovery and retention sweeps, the renderer, and
`SlackMessageRelayServiceImpl`, which owns a claim from lease renewal to the terminal status write. Kotest
`BehaviorSpec` + MockK; time is pinned with `createFixedUtcClock()` so every `now` the code binds into SQL equals
`DEFAULT_TEST_NOW`. One spec (`OutboxRelayRecoveryScenarioTest`) runs the real relay, poller and sweep over a
real `MessageOutboxRepository` on H2 with a `MutableClock`.

## Key Files
| File | Description |
|------|-------------|
| `DebeziumLogTailingProcessorTest.kt` | `consume()` with a real relay service from `createRelayService` over a strict `MessageOutboxRepository` mock (`stubClaimLifecycle()`), so dispatch counts are end-to-end. PENDING row → `claimPending(eventId, attemptCount = 0, now = DEFAULT_TEST_NOW)`, one dispatch, `completeClaim(attempt = 1, SUCCESS)`, success event; after-image without `attempt_count` (pre-V20) still maps; row already SUCCESS → nothing; claim lost → no dispatch; **redelivered record whose row is IN_PROGRESS → no claim, no dispatch**; `completeClaim` throwing on every retry → `consume` does not throw and a redelivery of the same record (row now IN_PROGRESS) does not send again; dispatch failure → FAILURE written; rate limit → no status write (the row is deferred). Tombstone, delete and IN_PROGRESS after-images are ignored; unmappable after-image / malformed `eventId` throw `CdcRecordParseException`. Also the dead-letter recoverer from `cdcDeadLetterRecoverer`: a record with a value-deserialization header goes through the byte-array template to `cdc.code_companion.outbox_message-dlt` with no partition and the original bytes; a parse failure of a deserialized `Envelope` goes through the JSON template; a failed dead-letter send does not throw. `CdcDeadLetterRecovery`: built from a JSON template it yields a `DeadLetterPublishingRecoverer` and `destroy()` closes its own bytes producer factory; `deadLetterBytesProducerFactory` keeps the JSON producer's settings and swaps only the value serializer; without a template records are logged, dropped and counted with `outcome=dropped`. `CountingRecordRecoverer`: passes the consumer to a consumer-aware delegate and counts once by topic and outcome after it returns; a delegate that throws is not counted |
| `PollingMessageProcessorTest.kt` | `createPollingProcessorFixture()`. No PENDING → no claim; three candidates → each claimed with `attemptCount = 0, now = DEFAULT_TEST_NOW` and forwarded as attempt 1; middle claim lost → exactly `a`, `c` forwarded; every claim lost → nothing forwarded |
| `OutboxRecoverySchedulerTest.kt` | Mixed won/lost `reclaimStuck` and `claimPending` → only winners forwarded, each as `attemptCount + 1`; `createdAt` 25 h old → `abandonStuck(eventId, attemptCount, olderThan = cutoff, now)` and no reclaim; `sendCount = 10` (default `maxSends`) abandoned while a row with `sendCount = 9` and 30 claims is reclaimed; lost abandon CAS → nothing re-dispatched; `AppConfig.Outbox.Polling` / `Health` reject zero or negative budgets and thresholds; empty sweep → relay untouched |
| `OutboxRelayRecoveryScenarioTest.kt` | H2 (`createOutboxJpaContext`) with the real repository, relay, `PollingMessageProcessor` and `OutboxRecoveryScheduler` on one `MutableClock`, and a `ScriptedMessageDispatcher`. (1) 16 rate-limited sends (Retry-After 30 s): a sweep 20 s later does not resend; 15 sweeps 5 min apart resend, the row stays `IN_PROGRESS` with `send_count = 0` past the 10-send budget, and the next sweep delivers it (`SUCCESS`, 17 claims). (2) `maxSends = 3` with transient-exhausted sends: exactly 3 sends, then `FAILURE`. (3) A `QueuedExecutor` holds the first claim past the stuck threshold; the sweep's claim and the stale one both run: one send, `send_count = 1`, `SUCCESS` |
| `SlackMessageRelayServiceImplTest.kt` | `saveOutboxMessage` → `port.toRow` + `save`; a failing `save` is called once and its original exception propagates (no `RetryException`). `dispatchClaimed`: success → `renewClaim` and `completeClaim(SUCCESS)` with the claim's attempt and the clock's time, success event keyed on the row `eventId`; `renewClaim = 0` (row reclaimed while queued) → no render, dispatch, write or event; renderer throws → FAILURE written, no dispatch; `completeClaim = 0` → no exception and no event; a throwing listener → one `completeClaim`, nothing thrown; rate-limited with Retry-After 30 s → two fixed ids deferred to distinct `updatedAt` in `[now − 270 s, now − 150 s)`, no terminal write or event; without Retry-After → `[now − 240 s, now − 120 s)`; transient-exhausted and a throwing dispatcher → renewed, nothing else written; `completeClaim` throwing → five attempts, nothing thrown, no event; malformed `eventId` → `completeClaim(FAILURE)` for the claim, no renew, render or event. `batchPendingMessages` runs every claim through the inline executor |
| `OutboxPayloadRendererTest.kt` | Real `CodecOutboundMessagePort` rows. Registered `Transport.SLACK` renderer receives the decoded `message`/`basicInfo`; `schemaVersion = 9999` → `IllegalArgumentException` mentioning `Unsupported outbox schemaVersion=9999` and `Refusing to dispatch`; empty renderer map → `IllegalStateException` `No renderer registered for transport=SLACK` |
| `OutboxRetentionSchedulerTest.kt` | `deleteTerminalOlderThan` batches `50, 50, 3` → total 103 in three calls with one cutoff |

## For AI Agents

### Working In This Directory
- `createOutboxRow` is a relaxed mock. **Never read one of its properties inside a `verify { }` block**
  (`row.eventId` there is recorded as a call on the row mock and the verification fails); keep the id in a
  local `val` and use that.
- The IN_PROGRESS-redelivery and status-write-failure cases in `DebeziumLogTailingProcessorTest` encode the
  CDC ownership contract (review S3/S4); do not flip them back to "re-dispatch".
- The "status write fails" cases walk the real `RetryService` policy (five attempts, ~1.5 s); they are the
  slowest cases here.
- `DEFAULT_TEST_NOW` is what every `now` parameter equals; stub with it when the spec is about the clock,
  and with `any()` otherwise.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.relay.*'
```
Fixtures: `application` testFixtures `outbox/OutboxTestFixtures.kt` (`DEFAULT_TEST_NOW`, `createFixedUtcClock`,
`createOutboxRow(eventId, status, createdAt, attemptCount, sendCount)`, `createPollingProcessorFixture`,
`createRelayService`, `stubClaimLifecycle`), `outbox/OutboxJpaTestContext.kt` (`createOutboxJpaContext`,
`MutableClock`, `ScriptedMessageDispatcher`, `QueuedExecutor`, `JdbcTemplate.outboxColumn`), infrastructure
testFixtures `createOutboxMessage` / `createPostEventPayloadContents`, and `service/relay/CdcEnvelopeCreator.kt` (`createCdcEnvelope`,
`createOutboxAfterImage`, `createCdcConsumerRecord`); `domain` testFixtures `createCommandBasicInfo`.

### Common Patterns
- Publish events are captured with `slot<Any>()` on `ApplicationEventPublisher.publishEvent` and narrowed with
  `shouldBeInstanceOf`.
- Local `data class *Fixture` bundles when a spec needs the SUT together with its mocks.

## Dependencies

### Internal
- `application/service/relay/*`, `application/configurations/AppConfig`, `KafkaConsumerConfiguration`
  (`cdcDeadLetterRecoverer`, `CdcDeadLetterRecovery`, `CountingRecordRecoverer`, `DEAD_LETTER_RECORDS_METRIC`,
  `deadLetterBytesProducerFactory`)
- `infrastructure/repository/outbox/MessageOutboxRepository`, `OutboundMessagePort`,
  `CodecOutboundMessagePort`, `Transport`, `schema/MessageStatus`, `dto/*`,
  `impl/command/event/MessageDispatcher`, `failOutput`, `successOutput`, `impl/command/RATE_LIMITED_REASON`,
  `TRANSIENT_EXHAUSTED_REASON`, `RateLimitedOutput`, `impl/retry/RetryService`

### External
MockK, Kotest, Spring `ApplicationEventPublisher`, Spring Kafka (`KafkaOperations`, `KafkaTemplate`,
`DefaultKafkaProducerFactory`, `DeadLetterPublishingRecoverer`, `SerializationUtils`), Kafka clients
(`ProducerRecord`, `ConsumerRecord`, `ProducerConfig`, `ByteArraySerializer`), Spring JDBC `JdbcTemplate` and H2
(scenario spec).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
