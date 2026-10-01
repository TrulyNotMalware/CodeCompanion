<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-01 -->

# application/src/testFixtures/kotlin/dev/notypie/application/configurations

## Purpose
Builders for `AppConfig` fragments: the `AppConfig.Cve.TopicDefinition` that `CveTopicBootstrap` reads on
startup, and an `AppConfig` with every secret set, for the `toString()` masking spec.

## Key Files
| File | Description |
|------|-------------|
| `AppConfigCreator.kt` | `createAppConfigWithSecrets(slackToken, slackAppToken, signingSecret, mcpSigningSecret, githubToken, nvdApiKey, sidecarBearerSecret)` — an `AppConfig` with every secret-bearing field set to a distinct fixture value, for the masking spec |
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
