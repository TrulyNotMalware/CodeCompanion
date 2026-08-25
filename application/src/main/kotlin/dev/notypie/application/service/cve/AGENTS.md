<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# application/service/cve

## Purpose
The CVE watch lane, end to end: sync watched topics from config at boot, poll external sources on a
schedule, summarize each new event with AI exactly once, then deliver summaries to subscribers as
immediate DMs or a daily digest. Slash commands and modal contexts let users subscribe and query the
latest events; ops commands let admins activate/deactivate topics.

The whole lane is **feature-gated**: every bean here is declared conditionally in
`application/configurations/CveConfiguration.kt`, so when the feature is off the schedulers do not exist.

## Key Files
| File | Description |
|------|-------------|
| `CveTopicBootstrap.kt` | On `ApplicationReadyEvent`, upserts the YAML-declared topics into `cve_topic` (keyed by topic key) |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `collector/` | `CveCollector` — `@Scheduled(fixedDelay = 300_000)` poll of every active topic, ingesting raw events as `PENDING` |
| `ai/` | `AiSummarizer` port with `SidecarAiSummarizer` / `NoopAiSummarizer`, plus `CveSummaryWorker` and `CveSummaryPromptBuilder` |
| `notification/` | `CveNotificationDispatcher` — immediate and digest delivery through the outbox |
| `subscription/` | `CveSubscriptionService` + slash-command service for per-user topic subscriptions |
| `query/` | `CveLatestQueryService` and the `/cve latest` slash service |
| `ops/` | `CveOpsService` — admin topic activate/deactivate and lane status |

## For AI Agents

### Working In This Directory
Each stage owns a distinct exactly-once mechanism. Preserve them:
- **Collect** — a topic claims its collect window once via `CveCollectLedgerRepository`. A lost claim
  means another instance owns that window, so the topic is skipped this tick. Raw events are written
  with `insertIgnore`, making overlapping windows idempotent. Every topic runs inside its own
  `runCatching` so one bad source never aborts the rest; the ledger is pruned past its retention.
- **Summarize** — `CveSummaryWorker` first calls `resetStuck` to recover rows a crashed worker
  abandoned, then claims events one at a time by **claim-token CAS**, producing exactly one summary
  per event. A single failure is recorded as `FAILED` with backoff (bounded by `maxRetries`) and never
  aborts the batch.
- **Deliver** — `CveDeliveryRepository.findUndelivered` yields `(event, user)` pairs; each unit of work
  runs in its own transaction and writes an **outbox row**, so delivery inherits the outbox's
  exactly-once-style semantics. Per-topic mode decides timing: `IMMEDIATE` on a frequent tick,
  `DIGEST` bundled into one daily DM per user after a configured local time.

Other rules:
- `CveTopicBootstrap` deliberately **does not** re-sync the `active` flag for existing rows. After the
  first insert, `active` is owned by the `cve topic activate|deactivate` chat command, so a topic an
  admin disabled in chat is not silently re-enabled on reboot. YAML `active` seeds new inserts only.
  Invalid topic definitions fail the boot on purpose — a silently dropped topic just looks like a
  missing modal option.
- Adding a new source means a new `SourceAdapter` in `infrastructure/impl/cve/` (adapters are resolved
  by `supports(sourceType)`), not a change to `CveCollector`.
- `AiSummarizer` is a port. `NoopAiSummarizer` is the wiring used when no sidecar is configured —
  keep it working, it is what lets the lane run without an LLM.

### Testing Requirements
```bash
./gradlew :application:test --tests '*Cve*'
```
Specs live under `application/src/test/kotlin/dev/notypie/application/service/cve/`, mirroring these
subpackages. Repository-level exactly-once behaviour (claim CAS, `insertIgnore`, `resetStuck`) is
covered by H2-backed specs in `infrastructure/src/test/kotlin/dev/notypie/repository/cve/`; assert the
*orchestration* here and the *SQL semantics* there. Fixtures: `SummaryRequestCreator` (application),
`CveEventCreator` / `CveTopicCreator` (infrastructure), `CveTopicConfigCreator` (config).

### Common Patterns
- `@Scheduled(fixedDelay = ...)` ticks with per-item `runCatching` isolation.
- Tunables (`batchSize`, `maxRetries`, `backoffMinutes`, `stuckMinutes`, `windowMinutes`) are constructor
  parameters bound from `AppConfig.Cve` in `CveConfiguration` — do not hardcode them in the worker.
- `runInTx` / `TransactionTemplate` for per-unit transactions inside a scheduled batch.
- Injected `Clock` + `ZoneId` for digest timing.

## Dependencies

### Internal
- `infrastructure/repository/cve/` — topic, event, subscription, delivery, collect-ledger repositories
- `infrastructure/repository/outbox/` — outbound delivery
- `infrastructure/impl/cve/` — `SourceAdapter`, `NvdCveSourceAdapter`, `GithubReleaseSourceAdapter`
- `infrastructure/impl/agent/` — sidecar client behind `SidecarAiSummarizer`
- `domain/command/outbound/` — `OutboundMessage`, `MessageContent`, `ConversationTarget`
- `application/configurations/CveConfiguration.kt` — the feature gate and all bean wiring

### External
Spring scheduling + transactions, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
