<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-01 -->

# application/health

## Purpose
The outbox's operational signals: the Actuator `HealthIndicator` and the Micrometer gauges that Prometheus
scrapes. The health indicator It reports pending / in-flight counts and
ages and flips the application to `DOWN` when any outbox row has sat in `PENDING` (poller stalled) or
`IN_PROGRESS` (dispatcher claimed it and died) longer than the configured stuck threshold plus one recovery
sweep period, or when an `IN_PROGRESS` row has been sent at least `retryingSendThreshold` times (a row the
recovery sweep keeps re-sending). A row that is only rate-limited is never `DOWN`. `show-details:
when_authorized` hides those details from every caller (there is no Spring Security), so the gauges are what
alerting reads. The first six counters also back the `@bot status` chat report in
`service/ops/OpsStatusService`; the retrying counter is not in that report yet.

## Key Files
| File | Description |
|------|-------------|
| `OutboxHealthIndicator.kt` | `@Component class OutboxHealthIndicator(outboxRepository, clock: Clock, appConfig: AppConfig) : HealthIndicator`. `health()` computes `cutoff = now - slack.app.outbox.health.stuck-threshold-seconds` (default 300) and reads `countPending`, `countPendingOlderThan(cutoff)`, `findOldestPendingCreatedAt`, `countInProgress`, `countInProgressOlderThan(cutoff - RECOVERY_SWEEP_PERIOD_MILLIS)` (the sweep gets one period to pick a row up), `findOldestInProgressUpdatedAt`, `countInProgressWithSendsAtLeast(slack.app.outbox.health.retrying-send-threshold)` (default 3); `DOWN` when either stuck count or the retrying count > 0. Details: `pendingCount`, `stuckPendingCount`, `stuckCount` (legacy alias of `stuckPendingCount`), `oldestPendingAgeSeconds`, `inFlightCount`, `stuckInFlightCount`, `oldestInFlightAgeSeconds`, `stuckThresholdSeconds`, `retryingCount`, `retryingSendThreshold` |
| `OutboxMetrics.kt` | `@Component class OutboxMetrics(outboxRepository, clock, appConfig, meterRegistry)`. Registers supplier gauges that query the repository on every read: `outbox.messages{status=pending\|in_progress}` (`countPending` / `countInProgress`), `outbox.pending.oldest.age` (`TimeGauge`, from `findOldestPendingCreatedAt`), `outbox.in.progress.oldest.claim.age` (`TimeGauge`, from `findOldestInProgressUpdatedAt`, so it is the time since the last claim or renewal) and `outbox.retrying.messages` (`countInProgressWithSendsAtLeast(retrying-send-threshold)`). Ages are 0 when no row is in that status. Prometheus names: `outbox_messages`, `outbox_pending_oldest_age_seconds`, `outbox_in_progress_oldest_claim_age_seconds`, `outbox_retrying_messages` |

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
- **Why the in-flight cutoff has a sweep period of grace:** a deferred or transient row becomes eligible
  for the sweep exactly at the stuck threshold and the sweep runs every 60 s, so without the grace a
  healthy retry would read as stuck for up to a minute. Only a row the sweep failed to take within a full
  period counts.
- The indicator is a hot path for liveness/readiness probes: seven repository calls per probe. Do not add
  queries that scan the table; every call it makes today is a covered count/min lookup.
- Keep the report and `OpsStatusService.renderReport()` computing the same numbers from the same
  repository methods — the chat reply and the health endpoint must never disagree.
- Thresholds come from `AppConfig.Outbox.Health`; do not read `@Value` here.
- **Gauges read the database on every scrape** (five covered queries per scrape per replica). A query that
  throws makes Micrometer report `NaN` for that gauge, which is intended: a stale or zero age would hide an
  outage. Every replica reports the same table-wide numbers, so alert on `max(...)`, not `sum(...)`.
- Suggested alerts (not provisioned in this repository): `max(outbox_pending_oldest_age_seconds) > 120` for
  10 m (the CDC connector or the poller stopped; the recovery sweep then delivers PENDING rows only after the
  stuck threshold), `max(outbox_retrying_messages) > 0` for 15 m, and
  `sum(increase(kafka_dead_letter_handoffs_total[15m])) > 0` (a CDC record was handed to the `<topic>-dlt` publisher; whether the send succeeded is only in the spring-kafka log).

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.health.*'
```
`OutboxHealthIndicatorTest` (Kotest `BehaviorSpec`, MockK `MessageOutboxRepository`) pins time with
`createFixedUtcClock(now = ...)` from `src/testFixtures/kotlin/dev/notypie/application/outbox/OutboxTestFixtures.kt`
and asserts `UP` / `DOWN` plus every detail key. Add a case for any new detail key and for the
in-flight branch when changing the `DOWN` rule. `OutboxMetricsTest` reads each gauge from a `SimpleMeterRegistry`
over the same stubs (counts, ages from the fixed clock, zero ages on an empty outbox, `NaN` when the query
throws); `ApplicationContextSmokeTest` checks that the Prometheus scrape carries the four gauges.

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
