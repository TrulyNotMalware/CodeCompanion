<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-01 -->

# test

## Purpose
The `application` module's test source set. Most specs are plain Kotest `BehaviorSpec`s with MockK ports and
mirror the `src/main/kotlin` package layout one-to-one. The exceptions: `ApplicationContextSmokeTest` boots the
application context on H2, `configurations/` starts small `ApplicationContextRunner`s, and eleven specs use an
in-memory H2 with a real transaction manager built from testFixtures (`createH2DataSource` /
`createH2TransactionManager` / `createBoundedH2DataSource` / `createH2MeetingJpaStore` in `service/meeting/`,
`createOutboxJpaContext` in `outbox/`): `MeetingWriteJpaTransactionTest`, `MeetingServiceImplTest`,
`MeetingRescheduleServiceTest`, `SlackInteractionHandlerImplTest`, `OutboxRelayRecoveryScenarioTest`,
`DailyAgendaSchedulingServiceTest`, `MeetingReminderSchedulingServiceTest`, `StandupSummaryServiceTest`,
`CveNotificationDispatcherTest`, `TransactionTemplateExtTest` and `AgentConverseServiceTest` (as of 2026-10-01; any
case that claims a commit or a rollback belongs in this group). Nothing here starts EmbeddedKafka.
Shared builders live in the sibling `testFixtures/` source set (see `../testFixtures/AGENTS.md`).

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `kotlin/` | Kotlin spec sources rooted at `dev.notypie.application` (see `kotlin/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
