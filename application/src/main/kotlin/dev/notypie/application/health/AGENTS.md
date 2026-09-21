<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# application/health

## Purpose
The Actuator `HealthIndicator` for the transactional outbox. It reports pending / in-flight counts and
ages and flips the application to `DOWN` when any outbox row has sat in `PENDING` (poller stalled) or
`IN_PROGRESS` (dispatcher claimed it and died) longer than the configured stuck threshold. The same
counters back the `@bot status` chat report in `service/ops/OpsStatusService`.

## Key Files
| File | Description |
|------|-------------|
| `OutboxHealthIndicator.kt` | `@Component class OutboxHealthIndicator(outboxRepository, clock: Clock, appConfig = AppConfig()) : HealthIndicator`. `health()` computes `cutoff = now - slack.app.outbox.health.stuck-threshold-seconds` (default 300) and reads `countPending`, `countPendingOlderThan`, `findOldestPendingCreatedAt`, `countInProgress`, `countInProgressOlderThan`, `findOldestInProgressUpdatedAt`; `DOWN` when either stuck count > 0. Details: `pendingCount`, `stuckPendingCount`, `stuckCount` (legacy alias of `stuckPendingCount`), `oldestPendingAgeSeconds`, `inFlightCount`, `stuckInFlightCount`, `oldestInFlightAgeSeconds`, `stuckThresholdSeconds` |

## For AI Agents

### Working In This Directory
- Detail keys are consumed by dashboards; treat them as an API. `stuckCount` is kept only as an alias
  for older panels — remove it in its own PR, not as a drive-by.
- `clock` has no default, so Spring injects the module's only `Clock` bean:
  `slackRequestVerificationClock()` = `Clock.systemUTC()` in
  `security/SlackRequestVerificationConfiguration`. `now` is derived as
  `clock.instant().atZone(clock.zone).toLocalDateTime()` and compared against `created_at` /
  `updated_at`, which Hibernate stamps with `@CreationTimestamp` / `@UpdateTimestamp` in the JVM default
  zone (`-Duser.timezone=Asia/Seoul` in the Dockerfile). Keep the clock zone and the stamping zone
  aligned or the stuck cutoff is off by the zone offset.
- The indicator is a hot path for liveness/readiness probes: six repository calls per probe. Do not add
  queries that scan the table; every call it makes today is a covered count/min lookup.
- Keep the report and `OpsStatusService.renderReport()` computing the same numbers from the same
  repository methods — the chat reply and the health endpoint must never disagree.
- Thresholds come from `AppConfig.Outbox.Health`; do not read `@Value` here.

### Testing Requirements
```bash
./gradlew :application:test --tests '*OutboxHealthIndicatorTest*'
```
`OutboxHealthIndicatorTest` (Kotest `BehaviorSpec`, MockK `MessageOutboxRepository`) pins time with
`createFixedUtcClock(now = ...)` from `src/testFixtures/kotlin/dev/notypie/application/outbox/OutboxTestFixtures.kt`
and asserts `UP` / `DOWN` plus every detail key. Add a case for any new detail key and for the
in-flight branch when changing the `DOWN` rule.

### Common Patterns
- `Health.up()` / `Health.down()` builder chosen first, then `.withDetail(...)` for every metric so
  the detail set is identical in both states.
- `ageSeconds(at, now)` clamps to `0` and maps a null timestamp to `0` so JSON consumers never see a
  negative or missing age.

## Dependencies

### Internal
- `infrastructure/repository/outbox/MessageOutboxRepository` — count and oldest-timestamp queries
- `application/configurations/AppConfig` — `outbox.health.stuckThresholdSeconds`
- `application/security/SlackRequestVerificationConfiguration` — source of the injected `Clock`
- `application/service/ops/OpsStatusService` — chat twin of this report

### External
Spring Boot Actuator (`HealthIndicator`, `Health`), JDK `java.time`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
