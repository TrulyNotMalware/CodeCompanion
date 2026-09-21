<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# test/kotlin/dev/notypie/application/health

## Purpose
Spec for the actuator `OutboxHealthIndicator`, which turns outbox row counts and ages into an `UP`/`DOWN`
status with a detail map that dashboards and `@bot status` read.

## Key Files
| File | Description |
|------|-------------|
| `OutboxHealthIndicatorTest.kt` | `OutboxHealthIndicator.health()` with a MockK `MessageOutboxRepository` driven by `stubOutboxStatus(...)`, `createFixedUtcClock(DEFAULT_TEST_NOW)`, and `AppConfig.Outbox.Health(stuckThresholdSeconds = 300)`. Cases: no rows → `UP` and every detail key at 0 (`pendingCount`, `stuckPendingCount`, legacy `stuckCount`, `oldestPendingAgeSeconds`, `inFlightCount`, `stuckInFlightCount`, `oldestInFlightAgeSeconds`, `stuckThresholdSeconds = 300`); fresh pending → `UP` with `oldestPendingAgeSeconds = 60`; stuck pending → `DOWN`; oldest pending in the future → age clamped to 0; stuck `IN_PROGRESS` → `DOWN` driven by in-flight details; fresh in-flight → `UP`. |

## For AI Agents

### Working In This Directory
- `stuckCount` is kept as a legacy alias of `stuckPendingCount`; a case asserts both. Remove the alias in the
  indicator and this spec together.
- Ages are computed against the injected clock, so `now.minusSeconds(n)` must surface as exactly `n`.
- The indicator and repository are `given`-scoped; each `when` calls `stubOutboxStatus(...)` which re-stubs all
  six repository reads, so stale stubs from an earlier `when` cannot leak.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.health.*'
```
Fixtures used: `testFixtures/.../application/outbox/OutboxTestFixtures.kt` (`DEFAULT_TEST_NOW`,
`createFixedUtcClock`, `MessageOutboxRepository.stubOutboxStatus`).

### Common Patterns
- Assert on `result.status` and `result.details[key]` with `Long` literals — the details map stores `Long`s and
  an `Int` literal will not compare equal.

## Dependencies

### Internal
- `application/health/OutboxHealthIndicator.kt`, `application/configurations/AppConfig.kt`
- `infrastructure/repository/outbox/MessageOutboxRepository`

### External
Spring Boot actuator `Status`, MockK, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
