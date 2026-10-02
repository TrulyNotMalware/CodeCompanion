<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-10-01 -->

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
| `AppConfig.kt` | `@ConfigurationProperties(prefix = "slack.app")` root: `api`, `mode`, `meeting`, `standup`, `outbox`, `socket`, `agent`, `authorization`, `mcp`, `cve`, `ai`. Also declares `OutboxReaderStrategy` (`POLLING` / `CDC`). `Outbox` nests `Health(stuckThresholdSeconds = 300, retryingSendThreshold = 3)`, `Polling(batchSize = 100, stuckInProgressSeconds = 300, giveUpAfterHours = 24, maxSends = 10)` and `Retention(days = 14, batchSize = 1000)` (the outbox purge). `Health` and `Polling` `require` every value to be positive in `init`, so a bad binding fails startup. `retryingSendThreshold` and `maxSends` count real sends (`send_count`), not claims; they have no profile YAML line and run on these defaults |
| `conditions/Conditions.kt` | `Environment.extractAppConfig()` + `OnPollingConsumer`, `OnCdcConsumer`, `OnKafkaEventPublisher`, `OnApplicationEventPublisher` — bind `slack.app` early and match on mode |
| `ConsumerConfig.kt` | Picks the outbox reader (`PollingMessageProcessor` vs `DebeziumLogTailingProcessor`), the `EventPublisher` (`AppEventPublisher` vs `KafkaEventPublisher`), and the mode-independent `ErrorBroadcaster` (`StdoutErrorBroadcaster`, `ErrorBroadcasterConfig`). Both readers take the context's single `Clock` bean, the same one the outbox schedulers use, and hand the CDC reader the `MessageRelayService` bean |
| `KafkaConsumerConfiguration.kt` | `@EnableKafka`, container factory (`AckMode.RECORD`, takes the `consumerFactory` bean as a parameter because `KafkaConsumerConfiguration` is a lite `@Import`ed class and calling `consumerFactory()` would build a second instance), `ErrorHandlingDeserializer`, `stopImmediate = true` and `shutdownTimeout = CDC_LISTENER_SHUTDOWN_TIMEOUT` (60 s: finish only the record in hand on shutdown; the rest of the poll is uncommitted and goes to another pod), `DefaultErrorHandler(FixedBackOff(1s, 2))` → the `cdcDeadLetterRecovery(meterRegistry)` bean's recoverer (`CdcDeadLetterRecovery`: `cdcDeadLetterRecoverer` over two dead-letter templates, each on its own `deadLetterProducerFactory` — the JSON template's producer properties plus `max.block.ms = DEAD_LETTER_MAX_BLOCK` (5 s), the bytes one also with `ByteArraySerializer` — which the bean closes in `destroy()`; every failed dead-letter send, synchronous or asynchronous, increments the `codecompanion.cdc.dlt.publish.failures` counter (`METRIC_DLT_PUBLISH_FAILURES`) and logs ERROR with the source topic/partition/offset; log-only (and counted) when no `KafkaTemplate` exists; `CdcRecordParseException` is not retried), Micrometer observation conventions. The recovery bean is deliberately not typed `ProducerFactory` / `KafkaTemplate`: a bean of either type would make Boot's `@ConditionalOnMissingBean` producer factory and template back off. `CdcConsumerConfiguration` also declares the `cdcDeadLetterTopic` `NewTopic` |
| `AsyncConfig.kt` | `@EnableAsync` + `@Primary threadPoolTaskExecutor` (10 threads, queue 10 000) and the dedicated `relayTaskExecutor` (4 threads, queue = `relayQueueCapacity(appConfig)` = `outbox.polling.batch-size`, `AbortPolicy`, waits `CDC_LISTENER_SHUTDOWN_TIMEOUT` on shutdown so a dispatch running at SIGTERM can record its status) that `SlackMessageRelayServiceImpl` takes by `@Qualifier`. Only the poller and `OutboxRecoveryScheduler` submit to it (the CDC listener dispatches on its own thread), and both run on the shared `taskScheduler` threads, so overflow must never run on the caller: they claim only rows they reserved a dispatch slot for (`SlackMessageRelayServiceImpl` counts `relayQueueCapacity` slots, each held until a pool thread starts the claim, so the queue cannot overflow), and a claim the pool still rejects (a pool smaller than that capacity, or shutdown) is logged and left `IN_PROGRESS` with no send spent for the sweep to reclaim. `relayQueueCapacity` is the one place the pool's queue and the relay's slot count are both read from; `SchedulingWiringSmokeTest` checks them against the real bean. The bound keeps queue time short, but correctness does not depend on it: a task that outlives the stuck threshold loses its claim to the recovery sweep and its `renewClaim` fails, so it never sends. Deliberately does **not** override the event multicaster |
| `SchedulingConfig.kt` | `@EnableScheduling` for the meeting/standup/CVE/outbox jobs, plus the `taskScheduler` bean: a `ThreadPoolTaskScheduler` built by Boot's `ThreadPoolTaskSchedulerBuilder` (so `spring.task.scheduling.pool.size`, 4 in `application.yaml`, applies). The bean name makes Boot's `DefaultTaskSchedulerConfiguration` back off; without it, `spring.threads.virtual.enabled: true` (every profile) selects a `SimpleAsyncTaskScheduler`, which runs **all** fixed-delay jobs on one thread and ignores the pool size (review T1, measured with `/actuator/conditions`) |
| `AppConfig`-driven feature configs | `CveConfiguration.kt` (whole CVE lane), `AgentConfiguration.kt` (sidecar client + agent service), `McpServerConfiguration.kt` (MCP tools, gate, turn-token filter) |
| `RestClientConfiguration.kt` | Shared `RestClient` used by Slack and source adapters |
| `SlackRequestBuilderConfiguration.kt` | Slack request/template builder beans. `messageDispatcher` also takes the `MeterRegistry` and counts access-blocked sends on `METRIC_ACCESS_BLOCKED` (`codecompanion.slack.dispatch.access_blocked`, tag `error` = the Slack code) through the dispatcher's `onAccessBlocked` hook |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `conditions/` | The custom Spring `Condition`s that pick a deployment mode at context-refresh time, plus the `Environment.extractAppConfig()` helper they share (see `conditions/AGENTS.md`) |

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
- `CveConfiguration.githubReleaseSourceAdapter` WARNs at boot when `slack.app.cve.github.token` is blank
  and the declared active GitHub topics × `60 / max(window-minutes, 5)` reach GitHub's anonymous 60
  requests/hour (the 5 mirrors `CveCollector`'s 5-minute tick, `COLLECTOR_TICK_MINUTES`).
- Tunables belong in `AppConfig` and are passed as constructor parameters to the bean. Do not read
  `@Value` inside a service.
- New properties need a default in `AppConfig` **and** a line in the relevant profile YAML under
  `application/src/main/resources/`.
- **CDC dead-letter wiring** (`cdcDeadLetterRecoverer`): the destination is explicit, `<cdc topic>-dlt`
  (`deadLetterTopic`), with partition `-1`, so the dead-letter topic needs neither spring-kafka's implicit
  default suffix nor as many partitions as the source. Two templates, keyed by value class in order:
  `ByteArray` → a template over the JSON template's producer properties with `ByteArraySerializer` (its
  producer factory belongs to the `cdcDeadLetterRecovery` bean and is flushed and closed on shutdown), so a record
  whose value failed deserialization is parked as the original bytes (replayable), and `Any` → the JSON
  `KafkaTemplate`'s settings for a deserialized `Envelope` that failed to parse. Neither template is the app's own
  `KafkaTemplate`: both have a dedicated producer with `max.block.ms` 5 s, because the send blocks the listener
  thread while it waits for topic metadata (kafka-clients' default 60 s per poison record when the topic is missing
  or not authorized). `setFailIfSendResultIsError(false)`:
  a dead-letter send that fails (topic missing with broker auto-create off, ACL denied) is logged by the
  recoverer and the record still counts as recovered, so one poison record cannot make the partition
  redeliver forever. That is safe because the outbox row, not the Kafka record, is the source of truth: a
  row whose record is lost stays `PENDING` and `OutboxRecoveryScheduler` claims it after the stuck
  threshold. What is lost is the replayable copy on the dead-letter topic, so `MeteredDeadLetterPublishingRecoverer`
  wraps the template it is handed (`FailureReportingOperations`) and counts a send that throws as well as one whose
  future fails — alert on `codecompanion.cdc.dlt.publish.failures` > 0 (review O7, Codex R3-03). The topic itself is declared as a `NewTopic` (broker-default partitions and replication) so
  Boot's `KafkaAdmin` creates it at startup when the principal may create topics; where it may not, create
  `<cdc topic>-dlt` by hand.

### Testing Requirements
```bash
./gradlew :application:test
```
`SchedulingWiringSmokeTest` (`src/test/kotlin/dev/notypie/application/configurations/`) is the one Spring
wiring smoke test: an `ApplicationContextRunner` over the real `application.yaml` with
`spring.threads.virtual.enabled=true` asserts the `taskScheduler` is a `ThreadPoolTaskScheduler` (pool ≥ 2), that
a blocked fixed-delay job does not stop another, and that `relayTaskExecutor` rejects overflow (`AbortPolicy`).
Extend it when a wiring choice depends on which bean Boot picks. `ShutdownBudgetTest` pins the shutdown
budget layer by layer: the CDC container has `stopImmediate` and a 60 s `shutdownTimeout`, `relayTaskExecutor` awaits
at least that long, every profile's `spring.lifecycle.timeout-per-shutdown-phase` is at least that long, and
`k8s/deployment.yaml`'s grace period covers preStop + one record + 15 s. Otherwise correctness is mostly proven by the
behaviour specs of the beans it wires. When adding a `Condition`, unit-test it against a `MockEnvironment` and
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
