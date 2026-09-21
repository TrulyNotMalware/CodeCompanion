<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# application/src/testFixtures/kotlin/dev/notypie/application/outbox

## Purpose
Fixtures for the outbox relay and its health/ops read-outs: a fixed UTC clock, relaxed `OutboxMessage`
rows, a fully wired `PollingMessageProcessor`, and a MockK extension that stubs the six status queries on
`MessageOutboxRepository`. The package has no `src/main` counterpart — the production code sits in
`service/relay/` and `health/` — it groups fixtures by the outbox concern instead.

## Key Files
| File | Description |
|------|-------------|
| `OutboxTestFixtures.kt` | `DEFAULT_TEST_NOW` (2026-04-28T12:00), `createFixedUtcClock(now)`, `createOutboxRow(eventId)`, `PollingProcessorFixture` + `createPollingProcessorFixture(clock, batchSize = 100, stuckInProgressSeconds = 300, outboxRepository, relayService)`, `MessageOutboxRepository.stubOutboxStatus(pendingCount, stuckPendingCount, oldestPendingCreatedAt, inProgressCount, stuckInProgressCount, oldestInProgressUpdatedAt)` |

## For AI Agents

### Working In This Directory
- `createFixedUtcClock` is the shared clock for every time-sensitive service spec —
  `AgentConverseServiceTest`, `OpsStatusServiceTest`, `OutboxHealthIndicatorTest`,
  `PollingMessageProcessorTest`. Derive "before" and "after" instants from `DEFAULT_TEST_NOW`, never from
  `Instant.now()`.
- `createOutboxRow(eventId)` is a **relaxed MockK** of `OutboxMessage` with only `eventId` stubbed. Every
  other property returns MockK defaults (`0`, `""`, empty), so a spec that asserts on payload, status or
  timestamps must `every { ... }` them itself. Consumers: `PollingMessageProcessorTest`,
  `SlackMessageRelayServiceImplTest`, `CveNotificationDispatcherTest`, `DailyAgendaSchedulingServiceTest`,
  `MeetingReminderSchedulingServiceTest`, `StandupSchedulingServiceTest`, `StandupSummaryServiceTest`.
- `createPollingProcessorFixture` returns the processor together with the two collaborators it was built
  with, so a spec destructures `(outboxRepository, relayService, processor)` and stubs/verifies the same
  instances. The `outboxRepository` default is a strict `mockk()`; `relayService` is relaxed. Only
  `PollingMessageProcessorTest` uses it.
- `stubOutboxStatus` stubs `countPending`, `countPendingOlderThan(any())`, `findOldestPendingCreatedAt`,
  `countInProgress`, `countInProgressOlderThan(any())`, `findOldestInProgressUpdatedAt` in one call — used
  by `OutboxHealthIndicatorTest` and `OpsStatusServiceTest`. Extend it when the repository gains a new
  status query instead of stubbing per spec.

### Testing Requirements
No specs for the fixtures themselves; the relay, health and ops specs above exercise them.

### Common Patterns
- Receiver-style stub helpers (`fun MessageOutboxRepository.stubOutboxStatus(...)`) for collaborator mocks
  that need the same group of `every { }` clauses in several specs.
- `data class *Fixture` bundles when a builder must hand back its mocked collaborators alongside the SUT.

## Dependencies

### Internal
- `dev.notypie.application.configurations.AppConfig` (`Outbox.Polling`)
- `dev.notypie.application.service.relay.PollingMessageProcessor`, `SlackMessageRelayServiceImpl`

### External
- `dev.notypie.repository.outbox.MessageOutboxRepository`, `schema.OutboxMessage` from `:infrastructure`
- `io.mockk` (`mockk`, `every`)
- `java.time.Clock` / `ZoneOffset.UTC`

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
