<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-07 -->

# test/kotlin/dev/notypie/application/health

## Purpose
Specs for the actuator `OutboxHealthIndicator`, which turns outbox row counts and ages into an `UP`/`DOWN`
status with a detail map that dashboards and `@bot status` read, and for the `OutboxMetrics` gauges.

## Key Files
| File | Description |
|------|-------------|
| `OutboxHealthIndicatorTest.kt` | `OutboxHealthIndicator.health()` with a MockK `MessageOutboxRepository` driven by `stubOutboxStatus(...)`, `createFixedUtcClock(DEFAULT_TEST_NOW)`, and `AppConfig.Outbox.Health(stuckThresholdSeconds = 300, retryingSendThreshold = 3)`. Cases: no rows → `UP` and every detail key at 0 (`pendingCount`, `stuckPendingCount`, legacy `stuckCount`, `oldestPendingAgeSeconds`, `inFlightCount`, `stuckInFlightCount`, `oldestInFlightAgeSeconds`, `retryingCount`) plus `stuckThresholdSeconds = 300`, `retryingSendThreshold = 3`; fresh pending → `UP` with `oldestPendingAgeSeconds = 60`; stuck pending → `DOWN`; oldest pending in the future → age clamped to 0; stuck `IN_PROGRESS` → `DOWN` driven by in-flight details; fresh in-flight → `UP`; a deferred row 320 s old → `UP`, with the in-flight count queried at `now − 360 s` (threshold plus one sweep period) and the retrying count by sends ≥ 3; a just-reclaimed row with a fresh age but `retryingCount = 1` → still `DOWN`, queried with `countInProgressWithSendsAtLeast(3)`. An access-blocked hold 600 s ago → `DOWN` with `accessBlocked = true`, the hold's instant and a 1200 s window; 1800 s ago → `UP`. Agreement over `createOutboxRepositoryOver(rows)` (every query answered from one row list): for empty, a deferred row 330 s old, an in-flight row 400 s old, a fresh row sent three times, a stale PENDING row, a fresh one, and no rows with an access block 10 minutes ago, `OpsStatusService.renderReport()` (service built with `createStubTransactionManager()`, unused by `renderReport`)'s UP/DOWN equals the indicator's, and the pending, in-flight, retrying, access-blocked and oldest-claim-age gauges equal the indicator's details. |
| `OutboxMetricsTest.kt` | `OutboxMetrics` registered into a `SimpleMeterRegistry` over `stubOutboxStatus(...)` and `createFixedUtcClock(DEFAULT_TEST_NOW)`, with `retryingSendThreshold = 4`. Five pending (oldest 420 s), two in flight (oldest claim 75 s), one retrying → `outbox.messages{status}` 5 and 2, ages 420 and 75 s, retrying 1 queried with `sends = 4`; empty outbox → both ages 0; `findOldestPendingCreatedAt` throwing → that age gauge reads `NaN`; reading all five gauges → `countPending`, `countInProgress` and the retrying count queried once each |

## For AI Agents

### Working In This Directory
- `stuckCount` is kept as a legacy alias of `stuckPendingCount`; a case asserts both. Remove the alias in the
  indicator and this spec together.
- Ages are computed against the injected clock, so `now.minusSeconds(n)` must surface as exactly `n`.
- The indicator and repository are `given`-scoped; each `when` calls `stubOutboxStatus(...)` which re-stubs all
  seven repository reads, so stale stubs from an earlier `when` cannot leak.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.health.*'
```
Fixtures used: `testFixtures/.../application/outbox/OutboxTestFixtures.kt` (`DEFAULT_TEST_NOW`,
`createFixedUtcClock`, `MessageOutboxRepository.stubOutboxStatus`, `OutboxStatusRow`, `createOutboxRepositoryOver`).

### Common Patterns
- Assert on `result.status` and `result.details[key]` with `Long` literals for counts and ages — an `Int`
  literal will not compare equal. `retryingSendThreshold` is the one `Int` detail.
- The repository mock is shared by every `when`, so call counts accumulate: use `verify(atLeast = 1)`.

## Dependencies

### Internal
- `application/health/OutboxHealthIndicator.kt`, `application/configurations/AppConfig.kt`
- `infrastructure/repository/outbox/MessageOutboxRepository`

### External
Spring Boot actuator `Status`, MockK, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
