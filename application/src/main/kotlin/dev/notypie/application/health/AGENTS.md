<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-02 -->

# application/health

## Purpose
The outbox's operational signals: the Actuator `HealthIndicator` and the Micrometer gauges that Prometheus
scrapes. The health indicator It reports pending / in-flight counts and
ages and flips the application to `DOWN` when any outbox row has sat in `PENDING` (poller stalled) or
`IN_PROGRESS` (dispatcher claimed it and died) longer than the configured stuck threshold plus one recovery
sweep period, or when an `IN_PROGRESS` row has been sent at least `retryingSendThreshold` times (a row the
recovery sweep keeps re-sending). A row that is only rate-limited is never `DOWN`. `show-details:
when_authorized` hides those details from every caller (there is no Spring Security), so the gauges are what
alerting reads. It is also `DOWN` while the relay held a row for a Slack access error (bad token, revoked scopes) within
`accessBlockedWindowSeconds`. All three surfaces — the indicator, the gauges and the `@bot status` chat report in
`service/ops/OpsStatusService` — read one `OutboxHealthSnapshot`, so they cannot disagree.

## Key Files
| File | Description |
|------|-------------|
| `OutboxHealthIndicator.kt` | `data class OutboxHealthSnapshot` (the seven counts/ages plus the two thresholds, `lastAccessBlockedAt`, `accessBlockedWindowSeconds`, `accessBlocked`; `healthy` = no stuck PENDING, no stuck in-flight, no retrying row, no access block in the window) and `MessageOutboxRepository.readOutboxHealth(clock, health: AppConfig.Outbox.Health, accessBlockedTracker)`, the one verdict the indicator, `OutboxMetrics` and `OpsStatusService` read. `@Component class OutboxHealthIndicator(outboxRepository, clock: Clock, accessBlockedTracker: AccessBlockedTracker, appConfig: AppConfig) : HealthIndicator`. `health()` reads the snapshot, which computes `cutoff = now - slack.app.outbox.health.stuck-threshold-seconds` (default 300) and reads `countPending`, `countPendingOlderThan(cutoff)`, `findOldestPendingCreatedAt`, `countInProgress`, `countInProgressOlderThan(cutoff - RECOVERY_SWEEP_PERIOD_MILLIS)` (the sweep gets one period to pick a row up), `findOldestInProgressUpdatedAt`, `countInProgressWithSendsAtLeast(slack.app.outbox.health.retrying-send-threshold)` (default 3); `DOWN` when either stuck count or the retrying count > 0. Details: `pendingCount`, `stuckPendingCount`, `stuckCount` (legacy alias of `stuckPendingCount`), `oldestPendingAgeSeconds`, `inFlightCount`, `stuckInFlightCount`, `oldestInFlightAgeSeconds`, `stuckThresholdSeconds`, `retryingCount`, `retryingSendThreshold`, `accessBlocked`, `lastAccessBlockedAt` (ISO instant or `never`), `accessBlockedWindowSeconds` |
| `OutboxMetrics.kt` | `@Component class OutboxMetrics(outboxRepository, clock, accessBlockedTracker, appConfig, meterRegistry)`. Registers supplier gauges over `readOutboxHealth`; the six gauges of one scrape share one snapshot (reused for 1 s by `System.nanoTime`), so a scrape runs the seven outbox queries once: `outbox.messages{status=pending\|in_progress}` (`countPending` / `countInProgress`), `outbox.pending.oldest.age` (`TimeGauge`, from `findOldestPendingCreatedAt`), `outbox.in.progress.oldest.claim.age` (`TimeGauge`, from `findOldestInProgressUpdatedAt`, so it is the time since the last claim or renewal) and `outbox.retrying.messages` (`countInProgressWithSendsAtLeast(retrying-send-threshold)`), plus `outbox.access.blocked` (1 while this replica's last access-blocked hold is inside the window, else 0). Ages are 0 when no row is in that status. Prometheus names: `outbox_messages`, `outbox_pending_oldest_age_seconds`, `outbox_in_progress_oldest_claim_age_seconds`, `outbox_retrying_messages`, `outbox_access_blocked` |

## For AI Agents

### Working In This Directory
- Detail keys are consumed by dashboards; treat them as an API. `stuckCount` is kept only as an alias
  for older panels — remove it in its own PR, not as a drive-by.
- `clock` has no default, so Spring injects the module's only `Clock` bean:
  `slackRequestVerificationClock()` = `Clock.systemDefaultZone()` in
  `security/SlackRequestVerificationConfiguration`. `now` is derived as
  `clock.instant().atZone(clock.zone).toLocalDateTime()` and compared against `created_at` /
  `updated_at`. Hibernate stamps inserts in the JVM zone, and every native outbox write binds `now` from
  this same bean, so all writers and this reader share one clock.
- **Why the retrying counter exists:** each reclaim resets `updated_at`, so a row that keeps failing is
  never "stuck" for long. `send_count` counts only claims that reached a send (a rate-limit deferral takes
  its send back), so a row whose sends keep failing keeps the indicator `DOWN` until it is abandoned
  (`outbox.polling.max-sends`, default 10) or succeeds, while a merely rate-limited row, which the relay
  defers past `Retry-After`, stays `UP`.
- **Why access blocks need their own signal:** the relay holds a row Slack refused for the bot's token or
  workspace with `defer`, which takes the send back and pushes `updated_at` past the stuck threshold, so no count
  above ever sees it and the verdict would stay `UP` for up to 24 h of held messages. The relay records each hold in
  `service/relay/AccessBlockedTracker` (in memory, per replica); the verdict is `DOWN` while the last hold is younger
  than `slack.app.outbox.health.access-blocked-window-seconds` (default 1200), which outlasts the time a held row takes
  to come back (15 min wait + up to 2 min spread + one sweep), so a dead token stays `DOWN` between retries. A
  restarted replica is `UP` until it holds its first row. The indicator, the gauges and `OpsStatusService` must get
  the same `AccessBlockedTracker` bean. The indicator is not in the readiness group, so an access block never fails a
  rollout.
- **Why the in-flight cutoff has a sweep period of grace:** a deferred or transient row becomes eligible
  for the sweep exactly at the stuck threshold and the sweep runs every 60 s, so without the grace a
  healthy retry would read as stuck for up to a minute. Only a row the sweep failed to take within a full
  period counts.
- The indicator is not part of the liveness/readiness groups (the k8s probes and the deploy gate read those); it
  feeds the aggregate `/actuator/health`, which prod caches for 10 s (`management.endpoint.health.cache.time-to-live`),
  and the same seven reads back the Prometheus gauges on every scrape. Each read carries a 2 s
  `jakarta.persistence.query.timeout` hint (`HEALTH_QUERY_TIMEOUT_MILLIS` on `MessageOutboxRepository`), so a stuck
  database fails the call instead of hanging it (configuration pinned by `MessageOutboxRepositoryHintsTest`; the timeout
  itself was not exercised, H2 cannot block a count). Do not add queries that scan the table; every call it makes
  today is a covered count/min lookup.
- **Retrying rows keep the aggregate DOWN on purpose** (the review's "a single retrying row turns it DOWN" was kept as
  designed): it gates nothing now, and alerting reads `outbox_retrying_messages` with a duration instead.
- Never compute an outbox count or verdict outside `readOutboxHealth`; the report and `OpsStatusService.renderReport()` share it so they keep the same numbers from the same
  repository methods — the chat reply and the health endpoint must never disagree.
- Thresholds come from `AppConfig.Outbox.Health`; do not read `@Value` here.
- **Gauges read the database on every scrape** (the seven covered queries of one snapshot per scrape per
  replica). A query that throws fails the snapshot, so Micrometer reports `NaN` for every outbox gauge, which is intended: a stale or zero age would hide an
  outage. Every replica reports the same table-wide numbers, so alert on `max(...)`, not `sum(...)`.
- Suggested alerts (not provisioned in this repository): `max(outbox_pending_oldest_age_seconds) > 120` for
  10 m (the CDC connector or the poller stopped; the recovery sweep then delivers PENDING rows only after the
  stuck threshold), `max(outbox_retrying_messages) > 0` for 15 m, and
  `sum(increase(kafka_dead_letter_handoffs_total[15m])) > 0` (a CDC record was handed to the `<topic>-dlt` publisher) and `sum(increase(kafka_dead_letter_publish_failures_total[15m])) > 0` (that send failed, so the record exists nowhere but the log).

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.health.*'
```
`OutboxHealthIndicatorTest` (Kotest `BehaviorSpec`, MockK `MessageOutboxRepository`) pins time with
`createFixedUtcClock(now = ...)` from `src/testFixtures/kotlin/dev/notypie/application/outbox/OutboxTestFixtures.kt`
and asserts `UP` / `DOWN` plus every detail key. Add a case for any new detail key and for the
in-flight branch when changing the `DOWN` rule. `OutboxMetricsTest` reads each gauge from a `SimpleMeterRegistry`
over the same stubs (counts, ages from the fixed clock, zero ages on an empty outbox, `NaN` when the query
throws, one repository read for the gauges of a scrape); `ApplicationContextSmokeTest` checks that the Prometheus scrape carries the four gauges.

### Common Patterns
- `Health.up()` / `Health.down()` builder chosen first, then `.withDetail(...)` for every metric so
  the detail set is identical in both states.
- `ageSeconds(at, now)` clamps to `0` and maps a null timestamp to `0` so JSON consumers never see a
  negative or missing age.

## Dependencies

### Internal
- `infrastructure/repository/outbox/MessageOutboxRepository` — count and oldest-timestamp queries
- `application/configurations/AppConfig` — `outbox.health.stuckThresholdSeconds`, `outbox.health.retryingSendThreshold`
- `application/service/relay/OutboxRecoveryScheduler` — `RECOVERY_SWEEP_PERIOD_MILLIS`
- `application/security/SlackRequestVerificationConfiguration` — source of the injected `Clock`
- `application/service/ops/OpsStatusService` — chat twin of this report

### External
Spring Boot Actuator (`HealthIndicator`, `Health`), Micrometer (`Gauge`, `TimeGauge`, `MeterRegistry`), JDK `java.time`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
