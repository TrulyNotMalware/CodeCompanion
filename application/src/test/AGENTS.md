<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-15 -->

# test

## Purpose
The `application` module's test source set. Most specs are plain Kotest `BehaviorSpec`s with MockK ports and
mirror the `src/main/kotlin` package layout one-to-one. The exceptions: `ApplicationContextSmokeTest` and
`AgentTurnShutdownContextTest` boot the application context on H2, `configurations/` starts small `ApplicationContextRunner`s, and some specs use an
in-memory H2 with a real transaction manager built from testFixtures (`createH2DataSource` /
`createH2TransactionManager` / `createBoundedH2DataSource` / `createH2MeetingJpaStore` in `service/meeting/`,
`createOutboxJpaContext` in `outbox/`; a `grep` for those names lists the current set). As of 2026-10-13 they are
`MeetingWriteJpaTransactionTest`, `MeetingServiceImplTest`, `MeetingRescheduleServiceTest`,
`MeetingReminderSchedulingServiceTest`, `DailyAgendaSchedulingServiceTest`, `SlackInteractionHandlerImplTest`,
`SlackMentionEventHandlerImplTest`, `RoleLookupJpaTransactionTest`, `OutboxRelayRecoveryScenarioTest`,
`OutboxChainJpaTransactionTest`, `StandupSummaryServiceTest`, `StandupSchedulingServiceTest`,
`StandupRoutineOpsServiceTest`, `CveNotificationDispatcherTest`, `TransactionTemplateExtTest`,
`AgentConverseServiceTest`, `AgentAskThreadStarterTest`, `AgentUsageReportServiceTest`, `OpsStatusServiceTest`,
`GoogleTokenRevocationWorkerTransactionTest`, `CalendarSyncServiceTest` (four cases on `createH2TransactionManager`, two
on `createH2MeetingJpaStore`),
`CalendarConnectionServiceTest` (one case on `createH2TransactionManager`, one on `createH2MeetingJpaStore`) and `MeetingCalendarMirrorServiceTest`; any case that claims a commit
or a rollback belongs in this group. Nothing here starts EmbeddedKafka.
Shared builders live in the sibling `testFixtures/` source set (see `../testFixtures/AGENTS.md`).

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `kotlin/` | Kotlin spec sources rooted at `dev.notypie.application` (see `kotlin/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
