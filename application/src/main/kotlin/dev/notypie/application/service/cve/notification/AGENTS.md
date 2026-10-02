<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-02 -->

# application/service/cve/notification

## Purpose
The delivery stage of the CVE lane. `CveNotificationDispatcher` turns `DONE`-summarized events into
subscriber DMs through the transactional outbox, exactly once per `(event, user)` pair. A topic's
`CveDeliveryMode` decides timing: `IMMEDIATE` pairs go out on the next minute tick, `DIGEST` pairs are
bundled into one DM per user per day after a configured local send time. Feature-gated `@Bean` from
`configurations/CveConfiguration`.

## Key Files
| File | Description |
|------|-------------|
| `CveNotificationDispatcher.kt` | `class CveNotificationDispatcher(cveDeliveryRepository, outboxRepository, outboundMessagePort, transactionManager, batchSize, digestSendAt: LocalTime, digestZone: ZoneId, digestSummaryMaxLength, deliveryHorizonDays, clock)`. `@Scheduled(fixedDelay = 60_000) immediateTick()`: `findUndelivered(IMMEDIATE, since = dbNow() - horizon, doneBefore = now, limit = batchSize)` then per pair `runInTx { claim(eventId, userId) && enqueue(...) }`. `@Scheduled(fixedDelay = 60_000) digestTick()`: returns before `digestSendAt` in `digestZone`; `doneBefore` = today's send time converted to the system zone; reads `findUndeliveredByUser` (user-major) and groups pairs by user — a full page drops its last user to the next tick (it may have been cut short), and a full page held by one user reads the rest of that user's day once with `findUndeliveredForUser` (up to `batchSize × 10` pairs, `log.warn` at the bound) so their day still goes out as one digest — then per user `runInTx { claim each; the won events as one or more DMs }`. `enqueue` builds `CommandBasicInfo.forOutbound(publisherId = userId, channel = userId)` + `OutboundMessage.ChannelMessage` and calls `outboxRepository.save(outboundMessagePort.toRow(...))`; an immediate body is capped at `BODY_MAX_LENGTH` (2 900) with a truncation marker. `digestParts` packs the digest's event lines into bodies of at most `SlackBlockLimits.MESSAGE_BODY_BUDGET`, repeating the topic header when a topic spills into the next body, and stages them as one chained row (`outboundMessagePort.toChainHead`, headlined `CodeCompanion — CVE digest (i/n)` when there are several), so the relay posts the DMs in order; only one event line longer than a body is cut. Before claiming, a user's events are cut to the oldest ones whose escaped lines (each counted with its topic header and separators) fit `CHAIN_TEXT_BUDGET` (40,000, at least one event): the chain head carries the whole digest and its CDC update record must stay within 1 MiB, and a single user's day can be 500 events. The rest stays unclaimed and goes out in a later tick's digest (INFO log). Topic names, titles and summaries (the prod `NoopAiSummarizer` passes GitHub release bodies through) go through `templates/escapeMrkdwn()` before interpolation; `digestSummaryMaxLength` trims the raw summary and `capBody` (`truncateSectionText`, never splitting an entity) measures the escaped body. |

## For AI Agents

### Working In This Directory
- Claim and outbox save share one transaction (`runInTx`), and `CveDeliveryRepository.claim` must stay
  `REQUIRED` so it joins it. A save failure rolls the claim back and the pair (or the whole user bundle
  for digests) re-drives next tick; the ledger never records a delivery that did not enqueue.
- Two clocks on purpose: the `since` horizon bounds DB-stamped `created_at`, so it uses
  `cveDeliveryRepository.dbNow()`; `doneBefore` bounds app-stamped `updated_at`, so it uses the app
  clock. Do not "simplify" to one clock.
- The digest has no once-per-day ledger. Its cutoff is today's send time, so the first tick after it
  drains everything summarized before it and later ticks find nothing; events summarized after the
  cutoff roll into tomorrow. Changing `doneBefore` changes that guarantee. The digest reads user-major so
  one user's day is never spread over several ticks (and several digests); the immediate path keeps the
  event-major `findUndelivered` so the oldest events go first across users.
- **Every claimed event must reach a sent body.** A claimed pair already has its `cve_delivery` row, so an
  event cut off the end of a capped digest would never be sent again. The digest is split into bodies
  rather than truncated; the renderer cuts each body into sections, and a body within `MESSAGE_BODY_BUDGET`
  stays under the per-message block text budget. The immediate DM is one event and keeps the 2 900-char
  section cap (`capBody`).
- This class writes outbox rows directly (`outboxRepository.save`) instead of going through
  `OutboundMessageStager` + `EventPublisher` because there is no command transaction to hook a
  `BEFORE_COMMIT` listener onto. The scheduling services in `service/meeting` and `service/standup`
  use the same shape.
- All timing knobs are validated in `CveConfiguration.cveNotificationDispatcher` (`digest-send-at`
  must be `HH:mm`, `digest-timezone` a valid zone id, sizes > 0). The injected `clock` is the single
  application `Clock` bean; the digest gate applies `digestZone` to its instant, so the bean's zone is
  irrelevant there, but `immediateTick` converts with `ZoneId.systemDefault()`.

### Testing Requirements
```bash
./gradlew :application:test --tests '*CveNotificationDispatcherTest*'
```
`CveNotificationDispatcherTest` (Kotest `BehaviorSpec`, MockK) builds
`TransactionTemplate(transactionManager)` for real over a MockK `PlatformTransactionManager`
(`getTransaction` → relaxed status, `commit` / `rollback` `just Runs`), pins `Clock.fixed`, and asserts:
no DM when the claim is lost, one row per immediate pair, one row per user for small digests, a large
digest split into numbered bodies, staged as one chained row, that together name every claimed event, a
500-event day cut to the oldest events within `CHAIN_TEXT_BUDGET` with the rest left unclaimed, the pre-send-time early return,
`doneBefore` values, and the truncation marker. Use `CveEventCreator` /
`CveTopicCreator` from the infrastructure testFixtures for `UndeliveredCveEvent` inputs.

### Common Patterns
- `runInTx { ... }.onFailure { log } .onSuccess { count }` per unit with counters summarised in one
  `log.info` per tick.
- `companion object private const` headlines (`CodeCompanion — CVE alert` / `— CVE digest`) and limits.
- `groupBy` then per-group transaction for bundled deliveries.

## Dependencies

### Internal
- `application/common/TransactionTemplateExt` — `runInTx`
- `infrastructure/repository/cve/` — `CveDeliveryRepository` (`findUndelivered`, `findUndeliveredByUser`, `findUndeliveredForUser`, `claim`, `dbNow`),
  `UndeliveredCveEvent`, `CveDeliveryMode`
- `infrastructure/repository/outbox/` — `MessageOutboxRepository`, `OutboundMessagePort.toRow`
- `domain/command/dto/CommandBasicInfo.forOutbound`, `domain/command/outbound/`
- `application/configurations/CveConfiguration` — bean + validation of `AppConfig.Cve.Notification`

### External
Spring `@Scheduled`, Spring TX `TransactionTemplate`, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
