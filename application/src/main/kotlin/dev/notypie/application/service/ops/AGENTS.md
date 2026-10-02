<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-02 -->

# application/service/ops

## Purpose
Backs `@bot status`. Consumes the `StatusReportRequestEvent` the domain `StatusContext` emits, renders a
text report of outbox lag / in-flight counts (plus a CVE feed section when that feature is on) from the
same repository counters the actuator health indicator reads, and posts it to the channel the mention
came from. The same renderer feeds the MCP `get_status` tool.

## Key Files
| File | Description |
|------|-------------|
| `OpsStatusService.kt` | `@Service`. `@EventListener handleStatusReport(StatusReportRequestEvent)`: `runCatching { renderReport() }` with a fixed fallback text on failure, then stages `OutboundMessage.ChannelMessage` (`STATUS_REPORT`, headline `"CodeCompanion — outbox status"`) to `payload.responseBasicInfo.channel` and `eventPublisher.publishOne`. `internal fun renderReport(): String`: reads `readOutboxHealth` (`application/health`), the same snapshot and verdict as `OutboxHealthIndicator` — pending / in-flight counts, stuck counts, oldest-row ages, the retrying count (`*Retrying:* n (sent at least Nx, still in flight)`), the stuck threshold and `Health: UP/DOWN`; `cveSection()` appended only when `cve.enabled` — active topics, `PENDING` / `SUMMARIZING` backlog, `FAILED` split into retryable vs dead-letter at `ai.maxRetries`, latest collect window |

## For AI Agents

### Working In This Directory
- **Same numbers as `/actuator/health`.** `renderReport` reads `readOutboxHealth`, the snapshot
  `OutboxHealthIndicator` and the gauges read, so the verdict (stuck PENDING, in-flight past the threshold
  plus one sweep period, or a retrying row) is defined once. Do not recompute a count here.
- **`renderReport()` is `internal` for a reason:** `application/mcp/DomainReadTools.get_status` calls it so
  chat and MCP output never disagree. Text changes affect both surfaces.
- The reply is a regular channel message, not an ephemeral — operators scroll back through history, and
  the only trigger is a deliberate `@bot status` mention. Role gating (`OPERATIONS`) happens upstream in
  the command pipeline, not here.
- The CVE repositories are injected unconditionally (`JpaConfiguration` registers them regardless of
  `cve.enabled`); only the *rendering* of the section is gated. Do not wrap them in `Optional` or
  `@ConditionalOnBean`.
- Render failures are caught and reported as text ("Failed to read outbox status...") so the listener
  never throws into the mention's transaction. No transaction here — read-only counters.
- Events: consumes `StatusReportRequestEvent` (emitted by `domain/.../context/StatusContext`, lifted by
  `SlackIntentResolver`); produces one outbound via `OutboundMessageStager` + `publishOne`.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.ops.OpsStatusServiceTest'
```
Spec under `application/src/test/kotlin/dev/notypie/application/service/ops/`. Fixtures:
`MessageOutboxRepository.stubOutboxStatus(...)` and `createFixedUtcClock` from
`dev.notypie.application.outbox` (application testFixtures), `createCommandBasicInfo`,
`createSendSlackMessageEvent`. Assert the rendered text lines and that the staged `ChannelMessage` is
published; build the service with an `AppConfig` whose `cve.enabled` toggles the CVE section.

### Common Patterns
- Constructor-injected `Clock` and `AppConfig`, neither defaulted; `AppConfig` fields copied into
  `private val`s at construction.
- `buildString { appendLine(...) }` for Slack markdown; bullets are `• *Label:* value`.
- `runCatching { ... }.getOrElse { log.error(...); fallback }` for user-facing reports.

## Dependencies

### Internal
- `infrastructure/repository/outbox/MessageOutboxRepository` — `countPending`, `countPendingOlderThan`,
  `findOldestPendingCreatedAt`, `countInProgress`, `countInProgressOlderThan`,
  `findOldestInProgressUpdatedAt`
- `infrastructure/repository/cve/` — `CveTopicRepository.countActive`, `CveEventRepository.countByStatus`
  / `countFailedRetryable` / `countDeadLetter`, `CveCollectLedgerRepository.latestWindowStart`,
  `schema/CveSummaryStatus`
- `domain/command/entity/event/` — `StatusReportRequestEvent`, `EventPublisher.publishOne`
- `domain/command/outbound/` — `OutboundMessage.ChannelMessage`, `MessageContent.Text`,
  `ConversationTarget`, `OutboundMessageStager`; `domain/command/entity/CommandDetailType.STATUS_REPORT`
- `application/configurations/AppConfig` — `outbox.health.stuckThresholdSeconds`, `cve.enabled`,
  `ai.maxRetries`
- Consumers of the same output: `application/health/OutboxHealthIndicator`, `application/mcp/DomainReadTools`

### External
Spring context events, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
