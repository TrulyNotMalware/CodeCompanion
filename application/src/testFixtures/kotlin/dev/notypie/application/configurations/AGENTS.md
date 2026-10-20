<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-10-20 -->

# application/src/testFixtures/kotlin/dev/notypie/application/configurations

## Purpose
Builders for `AppConfig` fragments: the `AppConfig.Cve.TopicDefinition` that `CveTopicBootstrap` reads on
startup, an `AppConfig` with every secret set, for the `toString()` masking spec, and an `AppConfig` with a given
agent retention, for the retention purge spec.

## Key Files
| File | Description |
|------|-------------|
| `AppConfigBinding.kt` | `@EnableConfigurationProperties(AppConfig::class) class AppConfigBinding` for `ApplicationContextRunner` specs: binds `slack.app.*` from the runner's properties. Not a `@Configuration`, so `ApplicationContextSmokeTest`'s component scan does not pick it up |
| `AppConfigCreator.kt` | `createAppConfigWithSecrets(slackToken, slackAppToken, signingSecret, mcpSigningSecret, githubToken, nvdApiKey, sidecarBearerSecret)` — an `AppConfig` with every secret-bearing field set to a distinct fixture value, for the masking spec; `createAgentRetentionAppConfig(askTurnDays = 14, historyDays = 180, batchSize = 1000)` — an `AppConfig` whose `agent.retention` carries those values, for `AgentRetentionSchedulerTest` (2026-10-19); `createOutboxRetentionAppConfig(days = 7L, batchSize = 50)` builds `outbox.retention` the same way (2026-10-20) |
| `CveTopicConfigCreator.kt` | `createCveTopicConfigDefinition(key = "cve-java", displayName = "Java CVE", category = CVE, sourceType = NVD_CVE, sourceConfig = """{"cpe":"oracle:jdk"}""", deliveryMode = IMMEDIATE, active = true)` |

## For AI Agents

### Working In This Directory
- `createCveTopicConfigDefinition` is consumed by `application/src/test/.../service/cve/CveTopicBootstrapTest.kt` only.
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
