<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# test/kotlin/dev/notypie/application/service/cve/notification

## Purpose
Spec for `CveNotificationDispatcher`, the scheduled stage that turns undelivered (event, user) pairs into
outbox DMs: `immediateTick()` enqueues one DM per pair, `digestTick()` bundles a user's pairs into one DM
once the configured send time has passed. Every assertion is on the captured `OutboundMessage`, never on a
Slack payload.

## Key Files
| File | Description |
|------|-------------|
| `CveNotificationDispatcherTest.kt` | Plain Kotest `BehaviorSpec` + MockK, no Spring context. Immediate: claim won → one `ChannelMessage` to `U1`, headline `CodeCompanion — CVE alert`, markdown `*Java CVE* — Boom\n\nPatch now`; claim lost → no `toRow`, no `save`; first of two pairs throws on `save` → second pair still claimed and saved, `verifyOrder` proves claim/save interleave pair by pair; 3,500-char summary → body capped at 2,887 `x` + `\n…(truncated)`; null summary → title-only `*Alpha* — t`. Digest: clock at 08:00Z with `digestSendAt = 09:00` → repository never queried; after send time → captured `doneBefore` equals 09:00Z converted through `ZoneId.systemDefault()`; three pairs over two users → two DMs, `U1` grouped as `*Alpha*\n• *t1*\ns1\n• *t2*\ns2`; one won and one lost claim → only the won event in the single DM; `digestSummaryMaxLength = 10` truncates each summary; five 700-char summaries → total length `2900 + "\n…(truncated)".length`. Horizon: captured `since` equals `dbNow() - 7 days`. |

## For AI Agents

### Working In This Directory
- `dispatcherWith(...)` pins `deliveryRepository.dbNow()` to `DB_NOW` and defaults the app clock to
  `AFTER_SEND_AT` (10:00Z); only the "before send time" case overrides the clock with `BEFORE_SEND_AT`. The
  horizon case proves `since` derives from the DB clock, not the app clock — keep the two constants distinct.
- `stubOutbox()` echoes `save(any())` back with `answers { firstArg() }`. A bare `relaxed` repository returns
  an `Object` for the generic `JpaRepository.save`, which fails the covariant cast in main.
- The transaction manager is a file-local `stubTransactionManager()` (`getTransaction` → relaxed status,
  `commit`/`rollback` `just Runs`). Each pair runs in its own `runInTx`, which is what the failure-isolation
  case relies on.
- DMs are captured with `mutableListOf<OutboundMessage>()` + `capture(list)` on `outboundMessagePort.toRow`;
  a `slot` would keep only the last DM. The file-private `channelId()`/`channelText()` extensions do the casts.
- The digest cutoff assertion converts through `ZoneId.systemDefault()` because main builds the
  `LocalDateTime` cutoff from the system zone; the expected value moves with the JVM zone, so the case is
  zone-stable, but a naive `LocalDateTime.of(...)` replacement would not be.
- `2900`, `2887`, and `…(truncated)` encode the Slack section cap as implemented in main; changing the cap
  means touching three cases here plus `cve/query`.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.cve.notification.*'
```
Fixtures used: `application` testFixtures `outbox/OutboxTestFixtures.kt` (`createOutboxRow`),
`infrastructure` testFixtures `schema/CveEventCreator.kt` (`createUndeliveredCveEvent`).

### Common Patterns
- Every `findUndelivered` stub spells out `deliveryMode`, `since = any()`, `doneBefore = any()`,
  `limit = 50`; the constructor's `batchSize = 50` is what makes `limit = 50` match.
- Claim outcomes are stubbed per `(eventId, userId)`; mixing `returns true` and `returns false` for the same
  user is how the "won and lost" digest case is built.
- Failure injection on `save` uses a counter inside `answers { ... }` to throw on the first call only.

## Dependencies

### Internal
- `application/service/cve/notification/CveNotificationDispatcher.kt`, `application/common/runInTx`
- `infrastructure/repository/cve/CveDeliveryRepository`, `repository/outbox/MessageOutboxRepository`,
  `repository/outbox/OutboundMessagePort`, `repository/cve/schema/CveDeliveryMode`
- `domain/command/outbound/OutboundMessage.ChannelMessage`, `MessageContent.Text`

### External
MockK (`verifyOrder`, `slot`, list `capture`), Kotest, Spring `PlatformTransactionManager`/`TransactionStatus`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
