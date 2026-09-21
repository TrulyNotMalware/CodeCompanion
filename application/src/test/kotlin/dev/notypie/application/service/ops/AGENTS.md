<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# test/kotlin/dev/notypie/application/service/ops

## Purpose
Spec for `OpsStatusService.handleStatusReport`, the `/status` listener that renders outbox health (pending
and in-flight counts, oldest ages, stuck counts, UP/DOWN) and, when the CVE feature is on, a CVE section with
topic/event counts and the last collect window.

## Key Files
| File | Description |
|------|-------------|
| `OpsStatusServiceTest.kt` | Plain Kotest `BehaviorSpec` + MockK, clock `createFixedUtcClock(2026-04-29T12:00)`, `stuckThresholdSeconds = 300`. Healthy → `*Pending:* 0`, `*In-flight:* 0`, `UP`, and the published queue holds exactly the staged event; 7 pending (2 stuck, oldest 900 s) and 1 in-flight (1 stuck, oldest 600 s) → `*Pending:* 7 (oldest 900s ago, stuck 2)`, `*In-flight:* 1 (oldest 600s ago, stuck 1)`, `DOWN`; `countPending` throws → body contains `Failed to read outbox status`; CVE disabled (default) → no `CVE` substring. CVE enabled with `countActive = 3`, PENDING 4, SUMMARIZING 1, failed-retryable 2, dead-letter 1 → `*CVE topics:* 3 active`, `*CVE events:* 4 pending, 1 summarizing, 2 failed (retryable), 1 dead-letter`, `*CVE last collect window:* 2026-07-14 09:30` with the outbox section still present; `latestWindowStart` null → `never`. |

## For AI Agents

### Working In This Directory
- `outboxRepository.stubOutboxStatus(...)` is the `MessageOutboxRepository` extension from
  `OutboxTestFixtures.kt`; it stubs all six count/oldest queries with `threshold = any()`. Ages in the report
  are `now - oldest`, so the 900 s / 600 s expectations come from `now.minusSeconds(...)` in the stub.
- Mocks are shared across the `when` blocks of each `given`. The "repository throws" case only re-stubs
  `countPending`, and the next case calls `stubOutboxStatus()` again to reset it; reordering the `when`s
  changes what is stubbed.
- The CVE section is gated by `AppConfig.Cve(enabled = true)` and reads `AppConfig.Ai(maxRetries = 5)` for
  the retryable/dead-letter split; the disabled `given` passes `relaxed` CVE repositories that are never hit.
- The report is a `ChannelMessage` to the command channel of `createCommandBasicInfo()`; the spec asserts on
  `MessageContent.Text.markdown` substrings only.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.ops.*'
```
Fixtures used: `application` testFixtures `outbox/OutboxTestFixtures.kt` (`createFixedUtcClock`,
`stubOutboxStatus`), `domain` testFixtures `command/CommandDomainInputCreator.kt` (`createCommandBasicInfo`),
`infrastructure` testFixtures `impl/command/event/SlackEventTestFixtures.kt` (`createSendSlackMessageEvent`).

### Common Patterns
- `StatusReportRequestEvent(idempotencyKey, StatusReportPayload(responseBasicInfo), STATUS_REPORT)` is built
  inline once per `given` and reused across cases.
- Publication is checked by capturing `EventQueue<CommandEvent<EventPayload>>` and asserting
  `toList().single() shouldBe outboundStub` in the healthy case only.

## Dependencies

### Internal
- `application/service/ops/OpsStatusService.kt`, `application/configurations/AppConfig.Outbox.Health`,
  `AppConfig.Cve`, `AppConfig.Ai`
- `domain/command/entity/event/StatusReportRequestEvent`, `StatusReportPayload`, `EventPublisher`,
  `domain/command/outbound/OutboundMessageStager`
- `infrastructure/repository/outbox/MessageOutboxRepository`, `repository/cve/CveTopicRepository`,
  `CveEventRepository`, `CveCollectLedgerRepository`, `repository/cve/schema/CveSummaryStatus`

### External
MockK, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
