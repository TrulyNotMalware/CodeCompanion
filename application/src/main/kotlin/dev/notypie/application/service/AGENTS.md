<!-- Parent: ../../../../../../../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# application/service

## Purpose
Every use case in the system. Each subpackage owns one capability lane and composes `domain` behaviour
with `infrastructure` adapters. Nothing here talks to Slack directly — outbound work leaves as
`OutboundMessage`/`CommandIntent` effects that `CommandExecutor` resolves and stages, or as outbox rows
that the relay lane dispatches.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `command/` | `CommandExecutor` (drain → resolve → publish), `CommandRoleResolver`, `RoleManagementService` (`grant`/`revoke`/`roles`) |
| `mention/` | `AppMentionEventHandler` / `SlackMentionEventHandlerImpl` — routes `@bot ...` mentions |
| `interaction/` | `InteractionHandler` / `SlackInteractionHandlerImpl` — buttons, selects, view submissions |
| `meeting/` | Meeting use cases plus the daily-agenda and reminder schedulers |
| `standup/` | Routine setup, answer collection, nudging, summary posting, scheduler |
| `agent/` | `AgentConverseService` — one AI turn against the sidecar, persisted to `agent_turn_history` |
| `cve/` | CVE collection, AI summarization, subscription, query, notification (see `cve/AGENTS.md`) |
| `relay/` | Transactional-outbox relay: Debezium CDC listener, polling fallback, payload rendering |
| `ops/` | `OpsStatusService` — backs `@bot status` |

## Key Files
| File | Description |
|------|-------------|
| `command/CommandExecutor.kt` | The pipeline hub: `command.handleEvent()` → `drainIntents()` → `SlackIntentResolver.resolveAll` + `OutboundMessageStager.stage` → `EventPublisher.publishEvent` |
| `relay/PollingMessageProcessor.kt` | Fixed-rate (5s) outbox poller: recovers stuck `IN_PROGRESS`, then CAS-claims `PENDING` rows |
| `relay/DebeziumLogTailingProcessor.kt` | `@KafkaListener` on the CDC topic; consumes Debezium envelopes off the outbox table |
| `relay/OutboxPayloadRenderer.kt` | Renders a stored outbox envelope into the concrete transport call |
| `meeting/MeetingReminderScheduler.kt` / `MeetingReminderSchedulingService.kt` | Trigger vs. logic split for pre-meeting reminder batches |
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
- **Scheduler split.** Add the cron/fixed-rate annotation to the thin `*Scheduler`, put the logic in
  `*SchedulingService`, and unit-test the service. Keep jobs short — the shared scheduler pool is 4 threads.
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
target the `*SchedulingService`, never the `@Scheduled` wrapper.

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
