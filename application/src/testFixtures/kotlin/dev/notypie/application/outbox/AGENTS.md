<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-09-30 -->

# application/src/testFixtures/kotlin/dev/notypie/application/outbox

## Purpose
Fixtures for the outbox relay and its health/ops read-outs: a fixed UTC clock, relaxed `OutboxMessage`
rows, a fully wired `PollingMessageProcessor`, a real `SlackMessageRelayServiceImpl` builder, MockK
extensions that stub the claim lifecycle and the seven status queries on `MessageOutboxRepository`, and an
H2-backed Spring Data context for scenario specs that drive the real repository. The package has no `src/main` counterpart — the production code sits in
`service/relay/` and `health/` — it groups fixtures by the outbox concern instead.

## Key Files
| File | Description |
|------|-------------|
| `OutboxTestFixtures.kt` | `DEFAULT_TEST_NOW` (2026-04-28T12:00), `createFixedUtcClock(now)`, `createOutboxRow(eventId, status = PENDING, createdAt = DEFAULT_TEST_NOW, attemptCount = 0, sendCount = 0)`, `PollingProcessorFixture` + `createPollingProcessorFixture(batchSize = 100, outboxRepository, relayService, clock = createFixedUtcClock())` (the default `relayService` is a relaxed mock whose `freeDispatchSlots()` is `Int.MAX_VALUE`, since a relaxed `Int` of 0 would make every tick skip), `createRelayService(outboxRepository, outboundMessagePort, payloadRenderer, messageDispatcher, applicationEventPublisher, clock, relayTaskExecutor = inline, appConfig = AppConfig())` (real service, real `RetryService`), `MessageOutboxRepository.stubClaimLifecycle(renewed = 1, completed = 1, deferred = 1)` (`renewClaim` / `completeClaim` / `deferClaim` for any arguments), `MessageOutboxRepository.stubOutboxStatus(pendingCount, stuckPendingCount, oldestPendingCreatedAt, inProgressCount, stuckInProgressCount, oldestInProgressUpdatedAt, retryingCount)` |
| `OutboxJpaTestContext.kt` | `OutboxJpaTestConfiguration` (`@EnableJpaRepositories(basePackageClasses = [MessageOutboxRepository::class])`, uniquely named embedded H2, Hibernate `generateDdl` over the `outbox/schema` package only, `JpaTransactionManager`, `JdbcTemplate`) and `createOutboxJpaContext()`; `MutableClock(current = Instant.now(), zoneId = systemDefault)` with `now` and `advance(by)`; `ScriptedMessageDispatcher(outcomes, fallback)` answering the n-th `dispatch` with `outcomes[n]` or `fallback` and counting `calls`; `QueuedExecutor` (`execute` queues, `runAll()` drains in order, `size`); `JdbcTemplate.outboxColumn(eventId, column)` |

## For AI Agents

### Working In This Directory
- `createFixedUtcClock` is the shared clock for every time-sensitive service spec —
  `AgentConverseServiceTest`, `OpsStatusServiceTest`, `OutboxHealthIndicatorTest`,
  `PollingMessageProcessorTest`. Derive "before" and "after" instants from `DEFAULT_TEST_NOW`, never from
  `Instant.now()`.
- `createOutboxRow` is a **relaxed MockK** of `OutboxMessage` with `eventId`, `status`, `createdAt`,
  `attemptCount` and `sendCount` stubbed. Every other property returns MockK defaults (`0`, `""`, empty), so a spec that
  asserts on payload or `updatedAt` must `every { ... }` them itself. Reading its properties inside a
  `verify { }` block records calls on the mock; capture them in a `val` first. Consumers: `PollingMessageProcessorTest`,
  `SlackMessageRelayServiceImplTest`, `CveNotificationDispatcherTest`, `DailyAgendaSchedulingServiceTest`,
  `MeetingReminderSchedulingServiceTest`, `StandupSchedulingServiceTest`, `StandupSummaryServiceTest`.
- `createPollingProcessorFixture` returns the processor together with the two collaborators it was built
  with, so a spec destructures `(outboxRepository, relayService, processor)` and stubs/verifies the same
  instances. The `outboxRepository` default is a strict `mockk()`; `relayService` is relaxed. Only
  `PollingMessageProcessorTest` uses it.
- `createRelayService` is shared by `SlackMessageRelayServiceImplTest` and `DebeziumLogTailingProcessorTest`
  (which drives the real relay behind the CDC reader). Pair it with `stubClaimLifecycle()` on a strict
  repository mock.
- `createOutboxJpaContext` is not Spring Boot: it builds a plain `AnnotationConfigApplicationContext`, so no
  `application.yaml` or auto-configuration is involved and the application module needs no JPA test starter.
  `@CreationTimestamp` still stamps `created_at` with JVM time, which is why `MutableClock` starts at
  `Instant.now()` in the JVM zone. Close the context in `afterSpec`, and delete the rows between scenarios,
  since the sweep reads every row in the table. Used by `OutboxRelayRecoveryScenarioTest`.
- `stubOutboxStatus` stubs `countPending`, `countPendingOlderThan(any())`, `findOldestPendingCreatedAt`,
  `countInProgress`, `countInProgressOlderThan(any())`, `findOldestInProgressUpdatedAt`,
  `countInProgressWithSendsAtLeast(any())` in one call — used
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
- `dev.notypie.application.service.relay.PollingMessageProcessor`, `SlackMessageRelayServiceImpl`,
  `OutboxPayloadRenderer`; `dev.notypie.impl.command.event.MessageDispatcher`, `dev.notypie.impl.retry.RetryService`

### External
- `dev.notypie.repository.outbox.MessageOutboxRepository`, `schema.OutboxMessage` from `:infrastructure`
- `io.mockk` (`mockk`, `every`)
- `java.time.Clock` / `ZoneOffset.UTC`
- Spring Data JPA, Spring ORM (`LocalContainerEntityManagerFactoryBean`, `HibernateJpaVendorAdapter`,
  `JpaTransactionManager`), Spring JDBC (`EmbeddedDatabaseBuilder`, `JdbcTemplate`), H2 at runtime

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
