<!-- Parent: ../../../../../../../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# application/configurations

## Purpose
All Spring wiring lives here: the typed configuration tree (`AppConfig`), the custom `Condition`s that
select an implementation per deployment mode, and the `@Configuration` classes that declare beans for
the relay, Kafka, async execution, scheduling, the AI agent, the CVE lane, and the MCP server.

Most services in this project are **explicitly declared beans**, not component-scanned — this file set
is the map of what actually exists at runtime in a given profile.

## Key Files
| File | Description |
|------|-------------|
| `AppConfig.kt` | `@ConfigurationProperties(prefix = "slack.app")` root: `api`, `mode`, `meeting`, `standup`, `outbox`, `socket`, `agent`, `authorization`, `mcp`, `cve`, `ai`. Also declares `OutboxReaderStrategy` (`POLLING` / `CDC`) |
| `conditions/Conditions.kt` | `Environment.extractAppConfig()` + `OnPollingConsumer`, `OnCdcConsumer`, `OnKafkaEventPublisher`, `OnApplicationEventPublisher` — bind `slack.app` early and match on mode |
| `ConsumerConfig.kt` | Picks the outbox reader (`PollingMessageProcessor` vs `DebeziumLogTailingProcessor`), the `EventPublisher` (`AppEventPublisher` vs `KafkaEventPublisher`), and the `ErrorBroadcaster` (stdout vs Kafka) |
| `KafkaConsumerConfiguration.kt` | `@EnableKafka`, container factory, `ErrorHandlingDeserializer`, error handler, Micrometer observation conventions |
| `AsyncConfig.kt` | `@EnableAsync` + `threadPoolTaskExecutor`; deliberately does **not** override the event multicaster |
| `SchedulingConfig.kt` | Enables scheduling for the meeting/standup/CVE/outbox jobs |
| `AppConfig`-driven feature configs | `CveConfiguration.kt` (whole CVE lane), `AgentConfiguration.kt` (sidecar client + agent service), `McpServerConfiguration.kt` (MCP tools, gate, turn-token filter) |
| `RestClientConfiguration.kt` | Shared `RestClient` used by Slack and source adapters |
| `SlackRequestBuilderConfiguration.kt` | Slack request/template builder beans |

## For AI Agents

### Working In This Directory
- **Do not make the event multicaster async.** `AsyncConfig` documents the reason: an async multicaster
  dispatches `@EventListener` callbacks on a pool thread, detaching them from the publishing thread's
  transaction. That breaks `@TransactionalEventListener(phase = BEFORE_COMMIT)`, whose
  `TransactionSynchronization` must be registered on the publishing thread's active transaction for the
  transactional outbox to commit atomically with domain writes. Listeners that must not block the HTTP
  thread opt in with `@Async` (routed to `threadPoolTaskExecutor`) instead.
- **Mode selection is condition-based, not profile-based.** `slack.app.mode.outboxReadingStrategy`
  chooses `POLLING` or `CDC`; publisher mode chooses application-event vs Kafka publishing. Add a new
  mode by adding a `Condition` in `conditions/` and a `@Conditional` bean — do not scatter
  `if (config.mode == ...)` checks into services.
- **Conditions bind config themselves** via `Binder.get(environment)`, because they run before
  `@ConfigurationProperties` beans exist. Keep `extractAppConfig()`'s `orElseGet { AppConfig() }`
  fallback — a missing config block must degrade to defaults, not fail condition evaluation.
- **Feature lanes are all-or-nothing.** `CveConfiguration` declares every CVE bean including the
  `@Scheduled` workers, so when the feature is off the schedulers do not exist at all. Follow that shape
  for new optional lanes rather than adding `if (enabled) return` guards inside a tick.
- Tunables belong in `AppConfig` and are passed as constructor parameters to the bean. Do not read
  `@Value` inside a service.
- New properties need a default in `AppConfig` **and** a line in the relevant profile YAML under
  `application/src/main/resources/`.

### Testing Requirements
```bash
./gradlew :application:test
```
There is no Spring-context smoke test for this package; correctness is mostly proven by the behaviour
specs of the beans it wires. When adding a `Condition`, unit-test it against a `MockEnvironment` and
assert both branches. `CveTopicConfigCreator` in `src/testFixtures/kotlin/` builds `AppConfig.Cve`
fixtures. After a wiring change, at minimum run the app locally with `--spring.profiles.active=local`
and confirm it boots.

### Common Patterns
- `@Configuration` class per feature lane; one `@Bean` function per collaborator, wired explicitly.
- `@Conditional(OnXxx::class)` + `@ConditionalOnMissingBean` so a test or another profile can override.
- Nested `data class` config with defaults inside `AppConfig`, bound by `@ConfigurationPropertiesScan`
  on `CodeCompanion.kt`.
- Enum-typed config (`OutboxReaderStrategy`, `CveDeliveryMode`, `CveSourceType`, `CveTopicCategory`)
  rather than strings.

## Dependencies

### Internal
- `application/service/relay/`, `application/service/cve/`, `application/service/agent/` — the beans declared here
- `application/security/` — filter registration for Slack and MCP
- `infrastructure/impl/command/` — `AppEventPublisher`, `KafkaEventPublisher`, dispatchers
- `infrastructure/exception/` — `ErrorBroadcaster` implementations
- `infrastructure/repository/outbox/` — `MessageOutboxRepository`

### External
Spring Boot autoconfigure/context, Spring Kafka, Micrometer observation, Spring AI MCP server starter.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
