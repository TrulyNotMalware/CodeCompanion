<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# application/src/testFixtures/kotlin/dev/notypie/application/configurations

## Purpose
Builder for the `AppConfig.Cve.TopicDefinition` fragment that `CveTopicBootstrap` reads on startup. Keeps
the seven-field constructor out of the spec body.

## Key Files
| File | Description |
|------|-------------|
| `CveTopicConfigCreator.kt` | `createCveTopicConfigDefinition(key = "cve-java", displayName = "Java CVE", category = CVE, sourceType = NVD_CVE, sourceConfig = """{"cpe":"oracle:jdk"}""", deliveryMode = IMMEDIATE, active = true)` |

## For AI Agents

### Working In This Directory
- Consumed by `application/src/test/.../service/cve/CveTopicBootstrapTest.kt` only.
- `sourceConfig` is a raw, nullable JSON string — the bootstrap parses it, so specs that cover parse
  failures pass malformed JSON here rather than building a map.
- The enums (`CveTopicCategory`, `CveSourceType`, `CveDeliveryMode`) live in infrastructure's
  `dev.notypie.repository.cve.schema`; this is why the fixture set depends on `project(":infrastructure")`.
- Add a sibling `create*` here when another `AppConfig.*` nested type needs a spec input; do not
  hand-build `AppConfig(...)` trees in specs (`../outbox/OutboxTestFixtures.kt` holds the one existing
  `AppConfig` assembly).

### Testing Requirements
No specs for the fixture itself; `CveTopicBootstrapTest` is the behavioural coverage.

### Common Patterns
- One `create*` per config fragment, every parameter defaulted, named arguments at the call site.

## Dependencies

### Internal
- `dev.notypie.application.configurations.AppConfig` (main)

### External
- `dev.notypie.repository.cve.schema.*` enums from `:infrastructure`

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
