<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-30 -->

# test/kotlin/dev/notypie/application/service/cve

## Purpose
Specs for the CVE watch lane. This directory holds the boot-time topic sync; each stage of the pipeline
(AI summarization, collection, delivery, ops, query, subscription) has its own subdirectory.

## Key Files
| File | Description |
|------|-------------|
| `CveTopicBootstrapTest.kt` | `CveTopicBootstrap(topics, cveTopicRepository).bootstrapTopics()`: two declarations → `upsert` called twice, every `AppConfig.Cve.TopicDefinition` field mapped onto `CveTopicDefinition` (`topicKey`, `displayName`, `category`, `sourceType`, `sourceConfig`, `deliveryMode`, `active`); blank key or blank display name → `IllegalArgumentException` before any repository call; keys `springBoot` + `springboot` → `IllegalArgumentException`, no upsert; a 76-char display name, a 65-char key, or 101 declared active topics → `IllegalArgumentException`, no upsert; 100 active topics at exactly 64-char keys and 75-char names plus 5 inactive → all 105 upserted (`countActive()` stubbed, since the bootstrap reads it after the upsert); empty list → repository untouched. |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `ai/` | Prompt builder, summary worker, noop and sidecar summarizers (see `ai/AGENTS.md`) |
| `collector/` | `CveCollector` window claiming and ingestion (see `collector/AGENTS.md`) |
| `notification/` | `CveNotificationDispatcher` immediate and digest DMs (see `notification/AGENTS.md`) |
| `ops/` | `CveOpsService` admin commands (see `ops/AGENTS.md`) |
| `query/` | `/latest` service and slash entry point (see `query/AGENTS.md`) |
| `subscription/` | Subscribe/unsubscribe/list service and slash entry point (see `subscription/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- Upserted definitions are collected with `mutableListOf<CveTopicDefinition>()` + `capture(list)`; a `slot`
  would keep only the second upsert.
- Validation happens before the first `upsert`, so a bad declaration must fail the whole boot — the spec
  asserts `verify(exactly = 0)` on the repository to lock that in.
- The lane is feature-gated in main (`AppConfig.Cve.enabled`); most subdirectory specs carry a
  "feature disabled" case that asserts zero repository traffic and (where a reply exists) a disabled message.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.service.cve.*'
```
Fixtures used: `testFixtures/.../application/configurations/CveTopicConfigCreator.kt`
(`createCveTopicConfigDefinition`).

### Common Patterns
- Topic-level test data always comes from `createCveTopicConfigDefinition(...)` (config shape) here and from
  `infrastructure` `schema/CveTopicCreator.kt` `createCveTopic(...)` (persisted shape) in the subdirectories.

## Dependencies

### Internal
- `application/service/cve/CveTopicBootstrap.kt`, `application/configurations/AppConfig.Cve`
- `infrastructure/repository/cve/CveTopicRepository`, `CveTopicDefinition`

### External
MockK, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
