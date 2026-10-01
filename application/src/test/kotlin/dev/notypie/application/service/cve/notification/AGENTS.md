<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-01 -->

# test/kotlin/dev/notypie/application/service/cve/notification

## Purpose
Spec for `CveNotificationDispatcher`, the scheduled stage that turns undelivered (event, user) pairs into
outbox DMs: `immediateTick()` enqueues one DM per pair, `digestTick()` bundles a user's pairs into one DM
(or numbered parts when they overflow a Slack section) once the configured send time has passed. Every assertion is on the captured `OutboundMessage`, never on a
Slack payload.

## Key Files
| File | Description |
|------|-------------|
| `CveNotificationDispatcherTest.kt` | Plain Kotest `BehaviorSpec` + MockK, no Spring context. Immediate: claim won → one `ChannelMessage` to `U1`, headline `CodeCompanion — CVE alert`, markdown `*Java CVE* — Boom\n\nPatch now`; claim lost → no `toRow`, no `save`; first of two pairs throws on `save` → second pair still claimed and saved, `verifyOrder` proves claim/save interleave pair by pair; 3,500-char summary → body capped at 2,887 `x` + `\n…(truncated)`; null summary → title-only `*Alpha* — t`; `R&D <team>` / `<!channel> v2.3.1` / a `<https://evil.example|Patch here>` summary → every piece escaped (`&amp;`, `&lt;`, `&gt;`); a 1,000-char `<` summary (under the cap raw, 4,000 escaped) → capped at `2900 + "\n…(truncated)".length` with no raw `<`; two digest events with 700-char `<` summaries → two parts (packed by escaped length), each ≤ 2,900, no marker. Digest: clock at 08:00Z with `digestSendAt = 09:00` → repository never queried; after send time → captured `doneBefore` equals 09:00Z converted through `ZoneId.systemDefault()`; three pairs over two users → two DMs, `U1` grouped as `*Alpha*\n• *t1*\ns1\n• *t2*\ns2`; one won and one lost claim → only the won event in the single DM; `digestSummaryMaxLength = 10` truncates each summary; a 3,500-char summary with `digestSummaryMaxLength = 5000` → only that line is capped at `2900 + "\n…(truncated)".length` and the next event starts a fresh part; six 700-char summaries (the Codex R3-01 / T15 repro) → every claimed `CVE-2026-000N` appears in a sent body, two parts headlined `(1/2)` / `(2/2)`, each ≤ 2,900 with no marker; a full page (`batchSize = 3`) over `U1`, `U1`, `U2` → only `U1` is served and `U2` is never claimed; a full page held by one user (`batchSize = 2`) → `findUndeliveredForUser(userId = "U1", since = dbNow() - 7 days, limit = 20)` is read once more and all three events are claimed and sent as one unnumbered digest (R4); with `batchSize = 1` the re-read asks for `limit = 10` and claims exactly what it returns. Horizon: captured `since` equals `dbNow() - 7 days`. |

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
  means touching the capped cases here plus `cve/query`.
- T15's contract is **inclusion, not length**: a digest case must assert that every claimed event's
  identifier reaches some sent body. A length-and-marker check alone passed while claimed events vanished.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.cve.notification.*'
```
Fixtures used: `application` testFixtures `outbox/OutboxTestFixtures.kt` (`createOutboxRow`),
`infrastructure` testFixtures `schema/CveEventCreator.kt` (`createUndeliveredCveEvent`).

### Common Patterns
- Immediate cases stub `findUndelivered`, digest cases stub `findUndeliveredByUser`; each spells out
  `deliveryMode`, `since = any()`, `doneBefore = any()`, `limit = N`, where `N` is `dispatcherWith`'s
  `batchSize` (default 50; the hold-back cases pass 3, 2 and 1). The single-user cases also stub
  `findUndeliveredForUser` at `limit = batchSize × 10`; the relaxed repository would otherwise return an
  empty re-read and nothing would be sent.
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
