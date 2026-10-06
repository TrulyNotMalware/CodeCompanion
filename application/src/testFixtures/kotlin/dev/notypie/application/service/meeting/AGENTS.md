<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-06 -->

# application/src/testFixtures/kotlin/dev/notypie/application/service/meeting

## Purpose
Builders for the scheduling side of the meeting lane: the `AgendaItem` the daily-agenda message renders,
the two repository projections (`AgendaCandidateMeeting`, `ReminderCandidateMeeting`) the scheduling
services read, and the real-transaction harnesses (JDBC and JPA) the meeting-write and agenda specs use.

## Key Files
| File | Description |
|------|-------------|
| `AgendaItemCreator.kt` | `createAgendaItem(startAt = 2026-05-04T10:00, title = "Sprint Planning")`, `createAgendaCandidateMeeting(meetingId = 1L, title, startAt = 2026-05-04T10:00, attendingUserIds = ["U_A"])`, `createReminderCandidateMeeting(meetingId = 1L, startAt (required), attendingUserIds = ["U_A"])` |
| `MeetingTransactionFixtures.kt` | `createH2DataSource()` (fresh in-memory H2 per call via `DriverManagerDataSource`), `createH2TransactionManager(dataSource = createH2DataSource())` (`DataSourceTransactionManager`), `createBoundedH2DataSource(maxConnections)` (a `HikariDataSource`, 250 ms `connectionTimeout`; close it in `afterSpec`), `createFixedClock(now: LocalDateTime)` (JVM default zone, like the production `Clock` bean), `createMeetingVersionConflict()` (`ObjectOptimisticLockingFailureException("meetings", 1L)`), `createParticipantDuplicateKeyViolation()` / `createNotNullViolation()` (`DataIntegrityViolationException` with a MariaDB-worded `SQLIntegrityConstraintViolationException` cause), `PlatformTransactionManager.failInsideParticipatingTx(exception): Nothing`, `CommitRecordingEventPublisher` (an `EventPublisher` that records each `OutboundMessageEnqueued` message in `afterCommit`; `committedMessages`, `committedEphemeralMarkdowns`), and `createH2MeetingJpaStore()` → `MeetingJpaStore` (`AutoCloseable`: `LocalContainerEntityManagerFactoryBean` over a fresh H2 scanning `dev.notypie.repository.meeting.schema` with `create-drop`, `JpaTransactionManager`, `JpaRepositoryFactory` repositories with a `PersistenceExceptionTranslationInterceptor` so flush failures arrive translated as in production, `meetingRepository` / `reminderRepository` impls (the reminder impl gets the store's `JpaTransactionManager` for its insert transaction), `inNewTransaction { }`, `currentEntityManager()`) |

## For AI Agents

### Working In This Directory
- Consumers: `DailyAgendaMessageBuilderTest` (`createAgendaItem`), `DailyAgendaSchedulingServiceTest`
  (`createAgendaCandidateMeeting`, `createH2DataSource`, `createH2TransactionManager`),
  `MeetingReminderSchedulingServiceTest` (`createReminderCandidateMeeting`), `MeetingServiceImplTest` and
  `MeetingRescheduleServiceTest` (the JDBC harness, the fixed clock, the pool and the integrity violations),
  `MeetingWriteJpaTransactionTest` (the JPA store).
- `JpaRepositoryFactory` alone adds neither exception translation nor `@Transactional` on repository
  methods; the store adds translation, and every spec call must run inside a transaction
  (`inNewTransaction` or a service's own template).
- `CommitRecordingEventPublisher` casts every payload to `OutboundMessageEnqueuedPayload`, so pair it with a
  real `SlackOutboundStager`; it throws outside an active transaction, which is the point — a staged reply
  must always ride a transaction.
- The H2 driver is not a compile dependency of this source set; `DriverManagerDataSource` loads it from the
  test runtime classpath (`:infrastructure` `runtimeOnly`).
- `createReminderCandidateMeeting.startAt` has no default on purpose: reminder eligibility is computed
  relative to the spec's fixed clock, so the caller must place the start time in relation to it.
- `AgendaCandidateMeeting` / `ReminderCandidateMeeting` are infrastructure projection types
  (`dev.notypie.repository.meeting`); `AgendaItem` is this module's own value in `service/meeting/`.
- Full `MeetingDto` / `Meeting` builders are domain fixtures
  (`domain/src/testFixtures/.../domain/meet/`) — do not duplicate them here.

### Testing Requirements
No spec for the fixture itself; the scheduling, message-builder and meeting-service specs are the coverage.

### Common Patterns
- Named-argument builders with deterministic `LocalDateTime` literals (never `now()`), so rendered agenda
  text is byte-stable across runs.

## Dependencies

### Internal
- `dev.notypie.application.service.meeting.AgendaItem` (main)

### External
- `dev.notypie.repository.meeting.AgendaCandidateMeeting`, `ReminderCandidateMeeting`,
  `dev.notypie.impl.command.event.OutboundMessageEnqueuedPayload` from `:infrastructure`
- Spring JDBC / TX (`DataSourceTransactionManager`, `DriverManagerDataSource`, `TransactionTemplate`,
  `TransactionSynchronizationManager`), Spring ORM (`ObjectOptimisticLockingFailureException`,
  `JpaTransactionManager`, `LocalContainerEntityManagerFactoryBean`, `HibernateJpaVendorAdapter`), Spring Data
  JPA `JpaRepositoryFactory`, HikariCP, and `dev.notypie.repository.meeting` (`JpaMeetingRepository`,
  `JpaMeetingReminderRepository`, the two impls, `schema.PARTICIPANT_UNIQUE_KEY`) from `:infrastructure`

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
