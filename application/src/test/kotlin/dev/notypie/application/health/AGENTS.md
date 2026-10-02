<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-01 -->

# test/kotlin/dev/notypie/application/health

## Purpose
Spec for the actuator `OutboxHealthIndicator`, which turns outbox row counts and ages into an `UP`/`DOWN`
status with a detail map that dashboards and `@bot status` read.

## Key Files
| File | Description |
|------|-------------|
| `OutboxHealthIndicatorTest.kt` | `OutboxHealthIndicator.health()` with a MockK `MessageOutboxRepository` driven by `stubOutboxStatus(...)`, `createFixedUtcClock(DEFAULT_TEST_NOW)`, and `AppConfig.Outbox.Health(stuckThresholdSeconds = 300, retryingSendThreshold = 3)`. Cases: no rows → `UP` and every detail key at 0 (`pendingCount`, `stuckPendingCount`, legacy `stuckCount`, `oldestPendingAgeSeconds`, `inFlightCount`, `stuckInFlightCount`, `oldestInFlightAgeSeconds`, `retryingCount`) plus `stuckThresholdSeconds = 300`, `retryingSendThreshold = 3`; fresh pending → `UP` with `oldestPendingAgeSeconds = 60`; stuck pending → `DOWN`; oldest pending in the future → age clamped to 0; stuck `IN_PROGRESS` → `DOWN` driven by in-flight details; fresh in-flight → `UP`; a deferred row 320 s old → `UP`, with the in-flight count queried at `now − 360 s` (threshold plus one sweep period) and the retrying count by sends ≥ 3; a just-reclaimed row with a fresh age but `retryingCount = 1` → still `DOWN`, queried with `countInProgressWithSendsAtLeast(3)`. Review F6: a recorded access block 1100 s ago with only an in-flight row → DOWN with `accessBlocked` true and `lastAccessBlockedAt`; one 1200 s ago → UP; never → `"never"`, window 1200 |

| `OutboxHealthAgreementTest.kt` | The "never disagree" contract: a MockK repository answers every count / oldest query from one list of rows (threshold arguments are honoured, not `any()`), and for each state `OutboxHealthIndicator.health().status == UP` must equal `OpsStatusService.renderReport()` containing `UP`. States: empty; an `IN_PROGRESS` row 330 s old (inside the sweep grace, both UP); one 400 s old (both DOWN); a fresh row with `send_count = 3` (both DOWN); a PENDING row 400 s old (both DOWN); a fresh PENDING row (both UP). The 330 s and `send_count = 3` states disagreed before `readOutboxHealth` was shared. Review F6: with a held row (future `updated_at`, no counter sees it) and the `AccessBlockedTracker` recorded 300 s ago both say DOWN, 1300 s ago (past the 1200 s window) both say UP |

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
`createFixedUtcClock`, `MessageOutboxRepository.stubOutboxStatus`).

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
