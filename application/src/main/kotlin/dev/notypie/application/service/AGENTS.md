<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-10-26 -->

# application/service

## Purpose
Every use case in the system. Each subpackage owns one capability lane and composes `domain` behaviour
with `infrastructure` adapters. Nothing here talks to Slack directly — outbound work leaves as
`OutboundMessage`/`CommandIntent` effects that `CommandExecutor` resolves and stages, or as outbox rows
that the relay lane dispatches.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `command/` | `CommandExecutor` (drain → resolve → publish), `CommandRoleResolver`, `RoleManagementService` (`grant`/`revoke`/`roles`) (see `command/AGENTS.md`) |
| `mention/` | `AppMentionEventHandler` / `SlackMentionEventHandlerImpl` — routes `@bot ...` mentions (see `mention/AGENTS.md`) |
| `interaction/` | `InteractionHandler` / `SlackInteractionHandlerImpl` — buttons, selects, view submissions (see `interaction/AGENTS.md`) |
| `meeting/` | Meeting use cases plus the daily-agenda and reminder schedulers (see `meeting/AGENTS.md`) |
| `standup/` | Routine setup, answer collection, nudging, summary posting, scheduler (see `standup/AGENTS.md`) |
| `agent/` | `AgentConverseService` — one AI turn against the sidecar, persisted to `agent_turn_history`; `AgentAskThreadStarter` — the durable start of an `/ask` turn: the `agent_ask_turn` ledger row written when the question is delivered, and the 60 s sweep that resumes or gives up unfinished turns; `AgentRetentionScheduler` — the hourly purge of finished ledger rows and of the turn and MCP tool-call audit (see `agent/AGENTS.md`) |
| `cve/` | CVE collection, AI summarization, subscription, query, notification (see `cve/AGENTS.md`) |
| `relay/` | Transactional-outbox relay: Debezium CDC listener, polling fallback, payload rendering (see `relay/AGENTS.md`) |
| `ops/` | `OpsStatusService` — backs `@bot status` (see `ops/AGENTS.md`) |
| `calendar/` | `/calendar connect \| disconnect \| status` — per-user Google OAuth connection, callback completion, async token revoke, disabled responder — and the Google Calendar mirror: the `MeetingCalendarMirror` hooks the meeting lane calls in its write transactions, the access-token cache and the 60 s `CalendarSyncScheduler` / `CalendarSyncService` that inserts, patches and deletes events; the hourly `GoogleOAuthStateRetentionScheduler` that deletes expired OAuth states (see `calendar/AGENTS.md`) |

## Key Files
| File | Description |
|------|-------------|
| `command/CommandExecutor.kt` | The pipeline hub: `command.handleEvent()` → `drainIntents()` → `SlackIntentResolver.resolveAll` + `OutboundMessageStager.stage` → `EventPublisher.publishEvent` |
| `relay/PollingMessageProcessor.kt` | Fixed-rate (5s) outbox poller (polling mode only): CAS-claims `PENDING` rows within the relay slots it reserved. Stuck `IN_PROGRESS` rows are recovered by `relay/OutboxRecoveryScheduler`, in both modes |
| `relay/DebeziumLogTailingProcessor.kt` | `@KafkaListener` on the CDC topic; consumes Debezium envelopes off the outbox table |
| `relay/OutboxPayloadRenderer.kt` | Renders a stored outbox envelope into the concrete transport call |
| `meeting/MeetingReminderScheduler.kt` / `MeetingReminderSchedulingService.kt` | Trigger vs. logic split for pre-meeting reminder batches |
| `calendar/CalendarSyncScheduler.kt` / `CalendarSyncService.kt` | Trigger vs. logic split for the Google Calendar mirror: claim-token CAS over `meeting_calendar_event`, HTTP outside any transaction, a per-tick time budget |
| `meeting/DailyAgendaScheduler.kt` / `DailyAgendaSchedulingService.kt` | Daily agenda digest |
| `standup/StandupScheduler.kt` / `StandupSchedulingService.kt` | Session open, per-member DM dispatch, nudge, cutoff |
| `standup/StandupSummaryService.kt` | Posts the channel summary and writes back `summaryMessageTs` |

## For AI Agents

### Working In This Directory
- **Effects, not side effects.** A service should end by handing effects to `CommandExecutor`, or by
  writing an outbox row. Direct Slack Web API calls from a service bypass idempotency and retry.
- **`CommandExecutor` re-throws.** Intent resolution and event publishing failures are logged and
  re-thrown so the caller's `@Transactional` boundary rolls back. Intents are *not* re-queued after a
  publish failure — publishers dispatch sequentially and a retry could duplicate publishes. Retries
  belong upstream (outbox relay, Kafka retry, Slack replay) keyed by the shared `idempotencyKey`.
- **Two relay drivers, one outbox.** `DebeziumLogTailingProcessor` (CDC) and `PollingMessageProcessor`
  (fallback) both implement `MessageProcessor`; which one is active is a profile/condition decision in
  `configurations/`. The `PENDING → IN_PROGRESS` CAS in `MessageOutboxRepository.claimPending` is the
  single source of truth, so racing pollers cannot double-dispatch. Never dispatch a row you did not
  claim.
- **Scheduled jobs.** Logic may live with its `@Scheduled` method when the class exposes a public entry point
  for specs — a once-function or a public tick (`relay/PollingMessageProcessor.pollPending`,
  `cve/collector/CveCollector.tick`, `cve/ai/CveSummaryWorker.tick`,
  `cve/notification/CveNotificationDispatcher.immediateTick` / `digestTick`). The thin `*Scheduler` + service
  split (`standup`, `meeting` reminder and daily agenda, `calendar` sync) is for ticks whose phases must fail
  independently: the scheduler wraps each phase in `containFailure` (decision #56). Keep jobs short — the shared
  scheduler pool is 4 threads. The sweeps and purges keep trigger and logic in one class (`relay/OutboxRecoveryScheduler`,
  `relay/OutboxRetentionScheduler`, `agent/AgentAskThreadStarter`, `agent/AgentRetentionScheduler`,
  `calendar/GoogleOAuthStateRetentionScheduler`): the `@Scheduled`
  method is a thin trigger around the failure containment (per table inside `AgentRetentionScheduler.purgeOnce`, and inside `GoogleOAuthStateRetentionScheduler.purgeOnce`), and
  the specs call `recoverOnce()` / `purgeOnce()` / `resumeOnce()`. Ticks contain failures with `containFailure`
  (infrastructure `common`), never `runCatching`: an `Exception` is logged and the next tick runs, an interrupt is
  rethrown with its flag, and an `Error` ends the tick and reaches the scheduler's error handler (2026-10-15). This is
  the one interrupt contract for every tick, the CVE collector and summary worker included: the tick ends with the
  flag set and Spring's `TaskUtils` handler logs "Unexpected error occurred in scheduled task" (normally only at
  shutdown); the next run is unaffected, because the pool clears the flag before its next task.
- **Slow calls are async.** The agent turn runs in an async application listener; do not move an LLM or
  Slack Web API call onto the inbound request thread or inside a transaction.
- Role gating goes through `CommandRoleResolver`. Resolution order is
  `slack.app.authorization.bootstrap-admins` (config, immutable from chat) → `user_command_role` row →
  default `USER`. Never cache a resolved role across a turn — a mid-conversation revoke must take effect.

### Testing Requirements
```bash
./gradlew :application:test
```
Every service here has a spec under `application/src/test/kotlin/dev/notypie/application/service/`.
Pattern: MockK the ports (`SlackIntentResolver`, `OutboundMessageStager`, `EventPublisher`, repositories),
inject a fixed `Clock`, then assert on the *effects* captured — not on Slack payloads. Scheduler specs
target the `*SchedulingService` for the logic; a wrapper is called only to pin its failure containment
(`StandupSchedulerTest`, the `DailyAgendaScheduler` cases in `DailyAgendaSchedulingServiceTest`).

### Common Patterns
- Interface + `Impl` pairs; the interface is the dependency other lanes take.
- `private val log = KotlinLogging.logger {}` or a file-level `private val logger`; failure logs carry
  `commandId`, `idempotencyKey`, and the effect counts — keep that shape, it is what makes the outbox
  debuggable.
- Constructor-injected `Clock` for anything time-dependent.
- Message text/blocks are built by dedicated builders (e.g. `DailyAgendaMessageBuilder`,
  `StandupDispatchMessageBuilder`) so they can be tested independently of scheduling.

## Dependencies

### Internal
- `domain/command/*` — `Command`, `CommandIntent`, `OutboundMessage`, `CommandOutput`
- `domain/meet/`, `domain/standup/` — aggregates and DTOs
- `infrastructure/impl/command/` — `SlackIntentResolver`, stagers, dispatchers, event publishers
- `infrastructure/repository/*` — outbox, meeting, standup, cve, agent, authorization, mcp
- `infrastructure/impl/agent/` — `AgentGateway` / `SidecarAgentClient`

### External
Spring context/scheduling/transaction, Spring Kafka (`@KafkaListener`), kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
