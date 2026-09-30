<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-30 -->

# application/service/cve/notification

## Purpose
The delivery stage of the CVE lane. `CveNotificationDispatcher` turns `DONE`-summarized events into
subscriber DMs through the transactional outbox, exactly once per `(event, user)` pair. A topic's
`CveDeliveryMode` decides timing: `IMMEDIATE` pairs go out on the next minute tick, `DIGEST` pairs are
bundled per user once a day after a configured local send time — one DM, or numbered parts when the day's
events do not fit one Slack section. Feature-gated `@Bean` from
`configurations/CveConfiguration`.

## Key Files
| File | Description |
|------|-------------|
| `CveNotificationDispatcher.kt` | `class CveNotificationDispatcher(cveDeliveryRepository, outboxRepository, outboundMessagePort, transactionManager, batchSize, digestSendAt: LocalTime, digestZone: ZoneId, digestSummaryMaxLength, deliveryHorizonDays, clock = Clock.systemDefaultZone())`. `@Scheduled(fixedDelay = 60_000) immediateTick()`: `findUndelivered(IMMEDIATE, since = dbNow() - horizon, doneBefore = now, limit = batchSize)` then per pair `runInTx { claim(eventId, userId) && enqueue(...) }`. `@Scheduled(fixedDelay = 60_000) digestTick()`: returns before `digestSendAt` in `digestZone`; `doneBefore` = today's send time converted to the system zone; `findUndeliveredByUser(DIGEST, ...)` (user-major page), holds back the page's last user when the page is full and holds more than one user, then per user `runInTx { claim each; digestParts(won events) → one outbox row per part }`. `digestParts` packs event lines under `*topic*` headers into parts of at most `BODY_MAX_LENGTH` (2 900), repeating a header when a topic spills over; a multi-part digest headlines each part `CodeCompanion — CVE digest (i/n)`. `enqueue` builds `CommandBasicInfo.forOutbound(publisherId = userId, channel = userId)` + `OutboundMessage.ChannelMessage` and calls `outboxRepository.save(outboundMessagePort.toRow(...))`; `capBody` caps an immediate body, or a single digest line too long for a part of its own, at 2 900 with a truncation marker |

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
  cutoff roll into tomorrow. Changing `doneBefore` changes that guarantee.
- The digest page is **user-major** (`findUndeliveredByUser`, `ORDER BY userId, eventId`) so `limit` can cut
  at most the last user on the page; a full page holds that user back to the next tick, which keeps each
  user's day in one tick. The exception is a page held by a single user (more pairs than `batchSize`):
  they are served in slices, one tick each, or they would starve. The immediate path keeps the event-major
  `findUndelivered` so the oldest events go first across users.
- **Every claimed event must reach a sent body.** A claimed pair already has its `cve_delivery` row, so an
  event cut off the end of a capped digest would never be sent again. The digest is therefore split into
  parts rather than truncated; only one event line longer than a part is capped (its title still leads
  the line). The 2 900-char cap is the Slack `section` limit (3 000): an oversized body would be rejected
  by Slack *after* the claim committed and the outbox would retry it forever.
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
no DM when the claim is lost, one row per immediate pair, one row per user for digests, every claimed
digest event present in a sent part, the full-page hold-back, the pre-send-time early return,
`doneBefore` values, and the truncation marker. Use `CveEventCreator` /
`CveTopicCreator` from the infrastructure testFixtures for `UndeliveredCveEvent` inputs.

### Common Patterns
- `runInTx { ... }.onFailure { log } .onSuccess { count }` per unit with counters summarised in one
  `log.info` per tick.
- `companion object private const` headlines (`CodeCompanion — CVE alert` / `— CVE digest`) and limits.
- `groupBy` then per-group transaction for bundled deliveries; a group may enqueue several outbox rows.

## Dependencies

### Internal
- `application/common/TransactionTemplateExt` — `runInTx`
- `infrastructure/repository/cve/` — `CveDeliveryRepository` (`findUndelivered`, `findUndeliveredByUser`,
  `claim`, `dbNow`),
  `UndeliveredCveEvent`, `CveDeliveryMode`
- `infrastructure/repository/outbox/` — `MessageOutboxRepository`, `OutboundMessagePort.toRow`
- `domain/command/dto/CommandBasicInfo.forOutbound`, `domain/command/outbound/`
- `application/configurations/CveConfiguration` — bean + validation of `AppConfig.Cve.Notification`

### External
Spring `@Scheduled`, Spring TX `TransactionTemplate`, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
