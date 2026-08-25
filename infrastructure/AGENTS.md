<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# infrastructure

## Purpose
Every concrete adapter. **All Slack coupling in the project lives here** — wire DTOs, inbound mappers,
intent resolution, outbound rendering, and modal/message templates — alongside the JPA repositories,
the Kafka publisher, the AI-sidecar client, and the CVE source adapters.

It depends on `:domain` only. It implements domain ports (`EventPublisher`, `OutboundMessageStager`,
repository contracts) and is consumed by `:application`. Produces a plain `jar` (`bootJar` disabled).

## Key Files
| File | Description |
|------|-------------|
| `build.gradle.kts` | Slack SDK (model/client/app-backend), Spring Data JPA + Kafka (as `api`, so they propagate), Jackson 3, MariaDB + H2 runtime drivers |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `src/main/kotlin/dev/notypie/impl/` | Transport and external-service adapters (see `src/main/kotlin/dev/notypie/impl/AGENTS.md`) |
| `src/main/kotlin/dev/notypie/repository/` | JPA repositories and schemas (see `src/main/kotlin/dev/notypie/repository/AGENTS.md`) |
| `src/main/kotlin/dev/notypie/templates/` | Slack message/modal builders and DSLs (see `src/main/kotlin/dev/notypie/templates/AGENTS.md`) |
| `src/main/kotlin/dev/notypie/configurations/` | `JpaConfiguration`, `RetryConfiguration` |
| `src/main/kotlin/dev/notypie/exception/` | `ErrorBroadcaster` port with `KafkaErrorBroadcaster` / `StdoutErrorBroadcaster`; `meeting/DatabaseException` |
| `src/main/kotlin/dev/notypie/common/` | `JsonMapper` (Jackson 3 `jsonMapper`), `JPAJsonConverter`, `PartitionKeyUtil` |
| `src/test/` | H2 + `EmbeddedKafka` integration specs; `TestApplication.kt` boots the slice |
| `src/testFixtures/` | Slack payload builders and schema creators reused by `:application` |

## For AI Agents

### Working In This Directory
- **This is the containment boundary.** If a Slack type would otherwise have to appear in `domain` or
  `application`, the mapping belongs here instead. `application` may reference infra ports and mappers,
  but Slack wire DTOs (`InteractionPayload`, `SlackEventCallBackRequest`, `Block`, `Element`, ...) should
  stay inside `impl/command/slack/`.
- **Rendering happens exactly once, at deliver time.** Outbound effects are persisted to the outbox
  transport-neutral and only rendered to a Slack wire payload by `SlackOutboundRenderer` when the relay
  dispatches them. The single exception is a modal: `views.open` needs the request thread's `trigger_id`,
  which expires ~3 seconds after issuance, so `SlackOutboundStager` renders and stages modals
  synchronously. Do not add a second rendering site.
- **The outbox status transition is the concurrency contract.** `claimPending`'s `WHERE status = 'PENDING'`
  is the source of truth; only rows that actually transitioned may be dispatched. `updated_at` is touched
  on claim so health indicators age `IN_PROGRESS` rows from claim time, not creation time.
- **Host-only meeting authorization is enforced here**, atomically, in the repository `WHERE` clause —
  not in the domain and not in the UI. Keep it that way when adding host-scoped operations.
- Jackson is declared per-module on purpose so `:domain`'s classpath stays Jackson-free. Do not move it
  to the root `subprojects` block.
- Spring Data JPA and Kafka are exposed as `api` here so `testFixtures` and `:application` inherit them.

### Testing Requirements
```bash
./gradlew :infrastructure:test
```
Integration specs run against **H2** and **`EmbeddedKafka`** — no external infrastructure. `TestApplication.kt`
plus `src/test/resources/application.yaml` define the slice. Repository specs come in pairs: a
`Jpa*RepositoryTest` for the query/CAS semantics and a `*RepositoryImplTest` for the mapping layer.
Parser/mapper specs use the fixtures in `src/testFixtures/kotlin/dev/notypie/impl/command/`
(`InteractionPayloadCreator`, `SlackEventCallBackRequestCreator`, `BlockActionPayloadCreator`,
`SlackEventTestFixtures`). `ViewSubmissionChannelRoutingRegressionTest` guards a previously-fixed
routing bug — never delete it as "redundant".

### Common Patterns
- Port (in `domain` or as a local interface) + `*Impl` adapter; the application layer depends on the port.
- Repository split: a Spring Data `Jpa*Repository` interface holding the queries, plus a `*RepositoryImpl`
  that maps between JPA schema classes and domain entities/DTOs.
- Schema classes live in a `schema/` subpackage next to their repository and are opened for JPA by the
  `allOpen` plugin config (`@Entity`, `@MappedSuperclass`, `@Embeddable`).
- Native queries for anything needing atomic CAS or `INSERT ... IGNORE`; JPQL/derived queries otherwise.
- Jackson access goes through the shared `jsonMapper` in `common/JsonMapper.kt`, not a new `ObjectMapper`.

## Dependencies

### Internal
- `:domain` — the only module dependency; implements its ports and maps to its entities

### External
Slack Java SDK (`slack-api-model`, `slack-api-client`, `slack-app-backend`), Spring Data JPA / Hibernate,
Spring Kafka, Spring Web (`RestClient`), Jackson 3, MariaDB + H2 drivers.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
