<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# application/service/cve/collector

## Purpose
The ingest stage of the CVE lane. `CveCollector` polls every active topic on a fixed schedule,
resolves the `SourceAdapter` for the topic's source type, claims the topic's collect window once in the
collect ledger, fetches, and `insertIgnore`s each raw event as a `PENDING` `cve_event` row for
`../ai/CveSummaryWorker`. It is a feature-gated `@Bean` from `configurations/CveConfiguration`.

## Key Files
| File | Description |
|------|-------------|
| `CveCollector.kt` | `class CveCollector(cveTopicRepository, cveEventRepository, cveCollectLedgerRepository, adapters: List<SourceAdapter>, windowMinutes)`. `@Scheduled(fixedDelay = 300_000) tick()`: `windowStart(now)` buckets the minute-of-hour to a multiple of `windowMinutes`; for each `findActiveTopics()` row, `collectTopic` picks `adapters.firstOrNull { it.supports(sourceType) }` (none → warn, skip), `claimWindow(topicId, windowStart)` (lost → skip), `adapter.fetch(topic)`, then `insertIgnore(topicId, externalId, title, rawContent, publishedAt)` per raw event and logs `fetched` / `inserted`; finally prunes the ledger with `deleteOlderThan(now - LEDGER_RETENTION_DAYS (7))`. Each topic and the prune run inside their own `runCatching` |

## For AI Agents

### Working In This Directory
- The window is claimed **before** the fetch on purpose: a failed fetch burns its window so a
  rate-limited feed is not hammered by retries inside the same bucket. Dedup (`insertIgnore` on
  `externalId`) plus the adapters' own lookback self-heal the gap. `CveConfiguration` enforces
  `nvd.lookback-minutes >= 2 * collector.window-minutes` for exactly this reason.
- `windowMinutes` must divide 60 (`CveConfiguration` rejects anything else); `windowStart` is what makes
  every instance ticking inside the same bucket race for one ledger row, so multi-replica deployments
  do not double-fetch. The tick cadence (5 minutes) is hard-coded and independent of the window size.
- `LocalDateTime.now()` is used directly; there is no injected `Clock`. `windowStart` is `internal` so
  the bucket math is unit-tested in isolation.
- A new source is a new `SourceAdapter` in `infrastructure/impl/cve/` registered as a `@Bean` in
  `CveConfiguration` — the collector receives every `SourceAdapter` bean and needs no change.
- Ledger retention (7 days) bounds the `cve_collect_ledger` table; `service/ops/OpsStatusService`
  shows `latestWindowStart()` from it as "last collect window".

### Testing Requirements
```bash
./gradlew :application:test --tests '*CveCollectorTest*'
```
`CveCollectorTest` (Kotest `BehaviorSpec`, MockK) asserts: adapter chosen by `supports`, no fetch when
the claim is lost, one `insertIgnore` per raw event, a throwing adapter does not stop the next topic,
the prune cutoff, and `windowStart` bucketing at several minutes. Use `CveTopicCreator` from the
infrastructure testFixtures for topics. The ledger's claim SQL is covered by the H2 specs under
`infrastructure/src/test/kotlin/dev/notypie/repository/cve/`.

### Common Patterns
- Per-item `runCatching { ... }.onFailure { log.error(ex) { ... } }` isolation inside a scheduled tick.
- Adapter lookup by capability (`supports(sourceType)`) over an injected `List`, not a `when` on the
  enum.
- `companion object const val` for retention; tunables via constructor.

## Dependencies

### Internal
- `infrastructure/impl/cve/SourceAdapter` (+ `NvdCveSourceAdapter`, `GithubReleaseSourceAdapter`)
- `infrastructure/repository/cve/` — `CveTopicRepository`, `CveEventRepository.insertIgnore`,
  `CveCollectLedgerRepository` (`claimWindow`, `deleteOlderThan`), `CveTopic`
- `application/configurations/CveConfiguration` — bean, window validation, adapter beans
- `application/service/cve/ai/` — consumes the `PENDING` rows written here

### External
Spring `@Scheduled`, kotlin-logging.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
