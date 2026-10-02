<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-01 -->

# application/health

## Purpose
The Actuator `HealthIndicator` for the transactional outbox. It reports pending / in-flight counts and
ages and flips the application to `DOWN` when any outbox row has sat in `PENDING` (poller stalled) or
`IN_PROGRESS` (dispatcher claimed it and died) longer than the configured stuck threshold plus one recovery
sweep period, or when an `IN_PROGRESS` row has been sent at least `retryingSendThreshold` times (a row the
recovery sweep keeps re-sending), or when the relay held a row for a Slack access error (bad token, revoked
scopes) within `accessBlockedWindowSeconds` (review F6). A row that is only rate-limited is never `DOWN`. The counters and the verdict are computed once, in
`readOutboxHealth`, and shared with the `@bot status` chat report (`service/ops/OpsStatusService`, also MCP
`get_status`), so the two cannot disagree.

## Key Files
| File | Description |
|------|-------------|
| `OutboxHealthSnapshot.kt` | `data class OutboxHealthSnapshot(pendingCount, stuckPendingCount, oldestPendingAgeSeconds, inFlightCount, stuckInFlightCount, oldestInFlightAgeSeconds, retryingCount, stuckThresholdSeconds, retryingSendThreshold, lastAccessBlockedAt: Instant?, accessBlockedWindowSeconds, accessBlocked)` with `healthy` = no stuck pending, no stuck in-flight, no retrying row and not `accessBlocked`, and `MessageOutboxRepository.readOutboxHealth(clock, health: AppConfig.Outbox.Health, accessBlockedTracker: AccessBlockedTracker)`, the single place that runs the seven reads below, reads the tracker and picks the cutoffs. `accessBlocked` = the tracker's last hold is less than `accessBlockedWindowSeconds` before `clock.instant()`. Ages clamp to 0 and a null timestamp reads as 0 |
| `OutboxHealthIndicator.kt` | `@Component class OutboxHealthIndicator(outboxRepository, clock: Clock, accessBlockedTracker: AccessBlockedTracker, appConfig = AppConfig()) : HealthIndicator`. `health()` = `readOutboxHealth(...)`, which computes `cutoff = now - slack.app.outbox.health.stuck-threshold-seconds` (default 300) and reads `countPending`, `countPendingOlderThan(cutoff)`, `findOldestPendingCreatedAt`, `countInProgress`, `countInProgressOlderThan(cutoff - RECOVERY_SWEEP_PERIOD_MILLIS)` (the sweep gets one period to pick a row up), `findOldestInProgressUpdatedAt`, `countInProgressWithSendsAtLeast(slack.app.outbox.health.retrying-send-threshold)` (default 3); `DOWN` when either stuck count or the retrying count > 0, or `accessBlocked`. Details: `pendingCount`, `stuckPendingCount`, `stuckCount` (legacy alias of `stuckPendingCount`), `oldestPendingAgeSeconds`, `inFlightCount`, `stuckInFlightCount`, `oldestInFlightAgeSeconds`, `stuckThresholdSeconds`, `retryingCount`, `retryingSendThreshold`, `accessBlocked`, `lastAccessBlockedAt` (ISO-8601 instant or `"never"`), `accessBlockedWindowSeconds` |

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
- **Why access blocks need their own signal (review F6/R3):** the relay holds a row Slack refused for the bot's
  token or workspace (`isAccessBlocked()`) with `defer`, which takes the send back and pushes `updated_at` past the
  stuck threshold, so no count above ever sees it and the verdict stayed `UP` for up to 24 h of held messages. The
  relay records each hold in `service/relay/AccessBlockedTracker` (in memory, per replica), and the verdict is `DOWN`
  while the last hold is younger than `slack.app.outbox.health.access-blocked-window-seconds` (default 1200). The
  window outlasts the time a held row takes to come back (15 min defer + up to 2 min spread + one sweep), so a dead
  token keeps it `DOWN` between retries; it clears one window after the last refused send. A restarted replica is
  `UP` until it holds its first row. This indicator is not in the readiness group (the deploy gate reads readiness),
  so an access block never fails a rollout.
- **Why the in-flight cutoff has a sweep period of grace:** a deferred or transient row becomes eligible
  for the sweep exactly at the stuck threshold and the sweep runs every 60 s, so without the grace a
  healthy retry would read as stuck for up to a minute. Only a row the sweep failed to take within a full
  period counts.
- The indicator is a hot path for liveness/readiness probes: seven repository calls per probe. Do not add
  queries that scan the table; every call it makes today is a covered count/min lookup.
- The chat reply and the health endpoint must never disagree: both call `readOutboxHealth` and use its
  `healthy`. Change a cutoff or a `DOWN` rule there, never in one caller. `OutboxHealthAgreementTest` pins the
  contract over a repository that answers every query from one row list (the sweep-grace window, the retrying
  counter, stuck and fresh pending, empty) and over an access block on either side of the window. Both callers must be
  handed the same `AccessBlockedTracker` bean; it has no default for that reason.
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
- `ageSeconds(at, now)` (in `OutboxHealthSnapshot.kt`) clamps to `0` and maps a null timestamp to `0` so JSON
  consumers never see a negative or missing age.

## Dependencies

### Internal
- `infrastructure/repository/outbox/MessageOutboxRepository` — count and oldest-timestamp queries
- `application/configurations/AppConfig` — `outbox.health.stuckThresholdSeconds`, `outbox.health.retryingSendThreshold`,
  `outbox.health.accessBlockedWindowSeconds`
- `application/service/relay/OutboxRecoveryScheduler` — `RECOVERY_SWEEP_PERIOD_MILLIS`
- `application/service/relay/AccessBlockedTracker` — when the relay last held a row for a Slack access error
- `application/security/SlackRequestVerificationConfiguration` — source of the injected `Clock`
- `application/service/ops/OpsStatusService` — chat twin of this report

### External
Spring Boot Actuator (`HealthIndicator`, `Health`), JDK `java.time`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
