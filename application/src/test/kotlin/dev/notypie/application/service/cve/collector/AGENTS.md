<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# test/kotlin/dev/notypie/application/service/cve/collector

## Purpose
Spec for `CveCollector`, the scheduled tick that walks active topics, claims a per-topic time window in the
collect ledger, fetches raw events through the matching `SourceAdapter`, and `insertIgnore`s them into the
event table. Also pins the `windowStart` bucket arithmetic that the ledger's uniqueness key depends on.

## Key Files
| File | Description |
|------|-------------|
| `CveCollectorTest.kt` | Plain Kotest `BehaviorSpec` + MockK, no Spring context. `tick()`: window claimed → `adapter.fetch(topic)` once, `insertIgnore(topicId = 7L, externalId = "R1", title = "Title", rawContent = "Body", publishedAt = null)` once, `deleteOlderThan(cutoff)` once; window already claimed → zero fetch and zero insert; first of two topics throws `source down` → second topic still ingested; `RSS` topic no adapter `supports` → no `claimWindow`, no fetch. `windowStart(now)`: 10:44:47.123 with 5-minute windows → 10:40:00; exactly 10:45 → 10:45; 10:59:59 → 10:55 (never crosses the hour); 60-minute window at 10:31 → 10:00. |

## For AI Agents

### Working In This Directory
- Mocks are strict (`mockk<...>()` without `relaxed`), so every call the collector makes must be stubbed.
  Forgetting `ledgerRepository.deleteOlderThan(cutoff = any()) returns 0` fails the whole `given`.
- `collectorWith(...)` fixes `windowMinutes = 5`; the bucket cases build their own collector via
  `collectorWithWindow(windowMinutes)` with `mockk()` repositories and `adapters = emptyList()` because
  `windowStart` is pure.
- Per-topic failure isolation is asserted only through the healthy topic's `insertIgnore`; there is no
  assertion on logging or on the failing topic's ledger row.
- `windowStart` is an internal function called directly; the `then` names are the contract for the
  `(topic_id, window_start)` ledger uniqueness.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.cve.collector.*'
```
Fixtures used: `infrastructure` testFixtures `schema/CveTopicCreator.kt` (`createCveTopic`) and
`schema/CveEventCreator.kt` (`createRawSourceEvent`). No `application` fixtures.

### Common Patterns
- `adapter.supports(sourceType = ...)` is stubbed per source type and `adapter.fetch(topic = topic)` per
  topic instance, so two topics sharing one adapter can succeed and fail independently.
- `verify(exactly = 1) { eventRepository.insertIgnore(...) }` repeats the full named-argument list; keep the
  argument names in sync with `CveEventRepository`.

## Dependencies

### Internal
- `application/service/cve/collector/CveCollector.kt`
- `infrastructure/impl/cve/SourceAdapter`, `repository/cve/CveTopicRepository`, `CveEventRepository`,
  `CveCollectLedgerRepository`, `repository/cve/schema/CveSourceType`

### External
MockK, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
