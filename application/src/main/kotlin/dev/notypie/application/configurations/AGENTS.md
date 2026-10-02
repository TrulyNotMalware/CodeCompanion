<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-10-02 -->

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
| `AppConfig.kt` | `@ConfigurationProperties(prefix = "slack.app")` root: `api`, `mode`, `meeting`, `standup`, `outbox`, `socket`, `agent`, `authorization`, `mcp`, `cve`, `ai`. Also declares `OutboxReaderStrategy` (`POLLING` / `CDC`). `Outbox` nests `Health(stuckThresholdSeconds = 300, retryingSendThreshold = 3)`, `Polling(batchSize = 100, stuckInProgressSeconds = 300, giveUpAfterHours = 24, maxSends = 10)` and `Retention(days = 14, batchSize = 1000)` (the outbox purge). `Health` and `Polling` `require` every value to be positive in `init`, so a bad binding fails startup. `retryingSendThreshold` and `maxSends` count real sends (`send_count`), not claims; they have no profile YAML line and run on these defaults. The same file holds `requireUsableSecrets()` and the `AppConfigSecretsCheck` configuration that calls it at startup: an unresolved `${...}` placeholder in the Slack token, app token, sidecar bearer secret, MCP signing secret, GitHub token or NVD key (the binder keeps the literal when the variable is missing, and k8s `envFrom` does not fail on a missing key) or a blank Slack token fails startup; the message names keys only. The signing secret has its own profile-aware check in `SlackRequestVerificationFilter`. `Api`, `Mcp`, `Cve.Github`, `Cve.Nvd` and `Agent.Sidecar` override the data-class `toString()` so a set secret prints as `****` (blank stays empty); a new secret field needs the same override or it leaks through the root `AppConfig.toString()` |
| `conditions/Conditions.kt` | `Environment.extractAppConfig()` + `OnPollingConsumer`, `OnCdcConsumer`, `OnKafkaEventPublisher`, `OnApplicationEventPublisher` — bind `slack.app` early and match on mode |
| `ConsumerConfig.kt` | Picks the outbox reader (`PollingMessageProcessor` vs `DebeziumLogTailingProcessor`), the `EventPublisher` (`AppEventPublisher` vs `KafkaEventPublisher`), and the mode-independent `ErrorBroadcaster` (`StdoutErrorBroadcaster`, `ErrorBroadcasterConfig`). Both readers take the context's single `Clock` bean, the same one the outbox schedulers use, and hand the CDC reader the `MessageRelayService` bean |
| `KafkaConsumerConfiguration.kt` | `@EnableKafka`, container factory (`AckMode.RECORD`, takes the `consumerFactory` bean as a parameter because `KafkaConsumerConfiguration` is a lite `@Import`ed class and calling `consumerFactory()` would build a second instance), `ErrorHandlingDeserializer`, `DefaultErrorHandler(FixedBackOff(1s, 2))` → the `cdcDeadLetterRecovery` bean's recoverer (`CdcDeadLetterRecovery`: `cdcDeadLetterRecoverer` over two dead-letter templates, JSON and bytes, each on its own `deadLetterProducerFactory` copied from the app template's producer configuration plus `max.block.ms` = `DEAD_LETTER_MAX_BLOCK` (5 s, so a missing topic does not block the listener thread 60 s per record) and closed by the bean in `destroy()`. The bean method takes the `KafkaTemplate<String, Any>` as a required parameter (Boot's auto-configured one in CDC mode, `KafkaProducerConfiguration`'s in Kafka publisher mode), so a context without one fails at startup instead of running a consumer that could only log and drop poison records. The recoverer is a `PoisonOnlyDeadLetterRecoverer` (2026-10-02): only a record whose failure has a `CdcRecordParseException`, `DeserializationException` or `MessageConversionException` in its cause chain is dead-lettered; any other failure that outlasted the two retries (a database outage) is logged and acknowledged, because the outbox row stays PENDING or IN_PROGRESS and `OutboxRecoveryScheduler` delivers it. Behind it a `CountingRecordRecoverer`, after the delegate returns, increments `kafka.dead.letter.handoffs{topic}` (Prometheus `kafka_dead_letter_handoffs_total`). It counts records handed to the dead-letter publisher, not records parked: `setFailIfSendResultIsError(false)` stays (a failed dead-letter send must not redeliver the partition forever), so a failed send (topic missing, ACL denied) still counts as a hand-off; the publisher wraps its template so that a send that throws or fails later increments `kafka.dead.letter.publish.failures{topic}` (Prometheus `kafka_dead_letter_publish_failures_total`) with an ERROR log. The chain stays `ConsumerAwareRecordRecoverer` so the DLT record keeps the consumer-group header; `CdcRecordParseException` is not retried), Micrometer observation conventions. The recovery bean is deliberately not typed `ProducerFactory` / `KafkaTemplate`: a bean of either type would make Boot's `@ConditionalOnMissingBean` producer factory and template back off. `CdcConsumerConfiguration` also declares the `cdcDeadLetterTopic` `NewTopic`. In Kafka publisher mode the imported, non-`@Configuration` `KafkaProducerConfiguration` builds the `ProducerFactory` from `kafkaProperties.buildProducerProperties()` (so `spring.kafka.producer.*` and `spring.kafka.properties.*`, e.g. security settings, reach the producer and the dead-letter templates that copy its configuration) and takes it as a `kafkaTemplate(producerFactory)` parameter: in this lite class a direct `producerFactory()` call built a second, unmanaged factory. Every producer factory closes within `PRODUCER_CLOSE_TIMEOUT_SECONDS` (5 s; spring-kafka's default is 30 s, and `stop()` closes synchronously, outside the phase timeout): `KafkaProducerConfiguration` and `deadLetterProducerFactory` set it, and the `producerCloseTimeoutCustomizer` bean applies it to Boot's factory when the Kafka event publisher is off. `ShutdownBudgetTest` counts three closes |
| `AsyncConfig.kt` | `@EnableAsync` + `@Primary threadPoolTaskExecutor` (10 threads, queue 10 000) and the dedicated `relayTaskExecutor` (4 threads, queue = `relayQueueCapacity(appConfig)` = `outbox.polling.batch-size`, `AbortPolicy`, `@DependsOn("entityManagerFactory")` so the context destroys it — waiting for its running dispatches — before the EntityManagerFactory and the DataSource close, 20 s shutdown wait `RELAY_SHUTDOWN_AWAIT_SECONDS`; the default executor waits `DEFAULT_EXECUTOR_SHUTDOWN_AWAIT_SECONDS` 10 s, and `k8s/deployment.yaml` budgets for both) that `SlackMessageRelayServiceImpl` takes by `@Qualifier`. Only the poller and `OutboxRecoveryScheduler` submit to it (the CDC listener dispatches on its own thread), and both run on the shared `taskScheduler` threads, so overflow must never run on the caller: they claim only rows they reserved a relay slot for (`claimWithReservedSlots`), and a claim the pool still rejects is left `IN_PROGRESS` with no send spent. The bound keeps queue time short, but correctness does not depend on it: a task that outlives the stuck threshold loses its claim to the recovery sweep and its `renewClaim` fails, so it never sends. Deliberately does **not** override the event multicaster |
| `SchedulingConfig.kt` | Enables scheduling for the meeting/standup/CVE/outbox jobs and declares the `ThreadPoolTaskScheduler` (`taskScheduler`, built from Boot's `ThreadPoolTaskSchedulerBuilder`, so `spring.task.scheduling.pool.size` applies). Without it, virtual threads make Boot use `SimpleAsyncTaskScheduler`, which ignores the pool size and runs every fixedDelay job on one thread |
| `AppConfig`-driven feature configs | `CveConfiguration.kt` (whole CVE lane; the NVD adapter's page-cap callback counts `cve.nvd.page.cap.reached{topic}`, and the GitHub adapter bean logs a WARN at boot when a blank `GITHUB_TOKEN` meets enough active GitHub topics to reach the anonymous 60/hour), `AgentConfiguration.kt` (sidecar client + agent service), `McpServerConfiguration.kt` (MCP tools, gate, turn-token filter, and the streamable HTTP transport, whose `JacksonMcpJsonMapper` wraps Boot's `JsonMapper` bean so the Kotlin module and any `spring.jackson.*` setting apply to MCP JSON-RPC) |
| `RestClientConfiguration.kt` | `restRequester` bean: `RestClientRequester` on `SLACK_API_BASE_URL` built from Boot's prototype `RestClient.Builder`, so its calls are observed (`http.client.requests`); used by `ModalTemplateBuilder`'s profile lookup |
| `SlackRequestBuilderConfiguration.kt` | Slack request/template builder beans. `messageDispatcher` takes the `MeterRegistry` and counts every outcome-unknown send on `METRIC_OUTCOME_UNKNOWN` (`codecompanion.slack.dispatch.outcome_unknown`, tag `method` = `chat.postMessage` / `chat.postEphemeral` / `response_url`, Prometheus `codecompanion_slack_dispatch_outcome_unknown_total`) through the dispatcher's required `onOutcomeUnknown` hook: such a row is written `FAILURE` and never resent, so the counter is the alert signal for a possibly lost or unconfirmed message. The `onAccessBlocked` hook counts `METRIC_ACCESS_BLOCKED` (`codecompanion.slack.dispatch.access_blocked`, tag `error` = one of the dispatcher's fixed Slack access codes, so low-cardinality) |

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
  thread submit to a dedicated bounded executor instead (`AgentConfiguration.agentTurnExecutor` for AI turns); no
  `@Async` method remains, so `threadPoolTaskExecutor` runs nothing today.
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
- **CDC dead-letter wiring** (`cdcDeadLetterRecoverer`): the destination is explicit, `<cdc topic>-dlt`
  (`deadLetterTopic`), with partition `-1`, so the dead-letter topic needs neither spring-kafka's implicit
  default suffix nor as many partitions as the source. Two templates, keyed by value class in order:
  `ByteArray` → a template over the JSON template's producer properties with `ByteArraySerializer` (its
  producer factory belongs to the `cdcDeadLetterRecovery` bean and is flushed and closed on shutdown), so a record
  whose value failed deserialization is parked as the original bytes (replayable), and `Any` → the JSON
  `KafkaTemplate` for a deserialized `Envelope` that failed to parse. `setFailIfSendResultIsError(false)`:
  a dead-letter send that fails (topic missing with broker auto-create off, ACL denied) is logged by the
  recoverer and the record still counts as recovered, so one poison record cannot make the partition
  redeliver forever. That is safe because the outbox row, not the Kafka record, is the source of truth: a
  row whose record is lost stays `PENDING` and `OutboxRecoveryScheduler` claims it after the stuck
  threshold. The topic itself is declared as a `NewTopic` (broker-default partitions and replication) so
  Boot's `KafkaAdmin` creates it at startup when the principal may create topics; where it may not, create
  `<cdc topic>-dlt` by hand.

### Testing Requirements
```bash
./gradlew :application:test
```
`ApplicationContextSmokeTest` boots the full context (polling and in-process modes only) and pins the
scheduler, the relay executor qualifier and the single `Clock`; `configurations/SchedulingConfigTest` covers the
scheduler under virtual threads. Otherwise correctness is proven by the behaviour specs of the beans it wires. When adding a `Condition`, unit-test it against a `MockEnvironment` and
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
