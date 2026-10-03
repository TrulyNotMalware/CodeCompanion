<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-01 | Updated: 2026-10-03 -->

# test/kotlin/dev/notypie/application/configurations

## Purpose
Specs for `src/main/kotlin/dev/notypie/application/configurations/` that need Spring to resolve which bean
wins: Boot auto-configuration plus one configuration class, started with `ApplicationContextRunner`.

## Key Files
| File | Description |
|------|-------------|
| `AppConfigSecretsTest.kt` | `requireUsableSecrets()`: a Slack token or sidecar secret that kept its `${...}` placeholder → `IllegalStateException` naming the key and never the token value; a blank Slack token → failure; a set token with blank optional secrets → passes. `toString()` of `createAppConfigWithSecrets()` contains none of the seven secret values and exactly seven `=****` masks while still printing non-secret settings; a blank secret renders empty (`token=,`) |
| `CdcConsumerConfigurationTest.kt` | `CdcConsumerConfiguration` in CDC mode (`spring.kafka.admin.auto-create=false`, `AppConfig` bound through the testFixtures `AppConfigBinding` (`@EnableConfigurationProperties`, deliberately not a `@Configuration` the smoke test would scan)): with `KafkaAutoConfiguration` the `cdcDeadLetterRecovery` bean wraps a `DeadLetterPublishingRecoverer`; with no `KafkaTemplate` bean the context fails to start and the failure names `KafkaTemplate` |
| `McpServerConfigurationTest.kt` | `McpServerConfiguration` with `slack.app.mcp.enabled=true`, `JacksonAutoConfiguration` and relaxed mocks for the tool collaborators: the transport's `JacksonMcpJsonMapper` wraps the context's `JsonMapper` bean (same instance) and reads JSON into a Kotlin data class |
| `KafkaProducerConfigurationTest.kt` | `KafkaAutoConfiguration` + `KafkaEventPublisherConfiguration` with `event-publisher=KAFKA` and a `SimpleMeterRegistry` bean (the imported dead-letter recovery bean counts records): the `KafkaTemplate` uses the `ProducerFactory` bean (same instance), and `spring.kafka.producer.acks` and a `spring.kafka.properties.*` key reach the producer configuration; that factory and the JSON and bytes dead-letter factories held by the `cdcDeadLetterRecovery` bean close within `PRODUCER_CLOSE_TIMEOUT_SECONDS`. A second runner with only `KafkaConsumerConfiguration` (CDC without the Kafka publisher) checks that Boot's factory gets the same timeout through the customizer. No broker is contacted |
| `ProfileYamlTest.kt` | Loads profile YAML with `YamlPropertySourceLoader`, no context: `slack-live` exposes only `health` and its `spring.datasource.password` is exactly `${DATABASE_USER_PWD}` (asserted as a boolean so a failure never prints a value); `prod` and `dev` expose `health,info,metrics,prometheus`; `prod` caches the aggregate health for 10s |
| `ShutdownBudgetTest.kt` | The record budget derived in code (`SLACK_DISPATCH_TIME_BOUND` = 39.66 s, `RELAY_RECORD_TIME_BOUND` = the profile lookup's `DEFAULT_READ_TIMEOUT` + 39.99 s); the CDC container's `stopImmediate` and `shutdownTimeout` and the CDC `lifecycleProcessor`'s Kafka-phase timeout equal `RECORD_SHUTDOWN_WAIT` while another phase keeps the configured 10 s; the relay executor's await and `RECORD_SHUTDOWN_WAIT` are at least one record; and `k8s/deployment.yaml`'s grace is at least preStop + 2 phases (prod `timeout-per-shutdown-phase`) + the Kafka phase + 3 producer closes + the relay, agent-turn (prod `shutdown-await-seconds`) and default executor waits + 10 s. A lazy-init context with `AsyncConfig`, `AgentConfiguration` and a probe bean named `entityManagerFactory` created after both executors: both executors are shut down when the probe is destroyed (`@DependsOn`). The real `agentTurnExecutor` with every thread busy and one turn queued: after `agentTurnIntake.stop()` a new submission is rejected and the queued turn still runs. With a 1 s wait and every thread blocked: `destroy()` discards the queued `AgentTurn` (its `onDiscard` runs, its `start` never does) and the running turns finish without a discard Stop order in one `AnnotationConfigApplicationContext`: the relay (`createRelayService`) and a `KafkaListenerEndpointRegistry` subclass that records `relay.isRunning` in `stop(callback)` — the relay is still running when the listener registry stops and stopped after close (fails with the relay at `SmartLifecycle.DEFAULT_PHASE`). |
| `SlackDispatchCounterWiringTest.kt` | `SlackRequestBuilderConfiguration` in an `AnnotationConfigApplicationContext` (lazy init) with `AppConfig`, `RetryService` and a `PrometheusMeterRegistry` bean: the `messageDispatcher` bean is an `ApplicationMessageDispatcher`, and invoking its `onOutcomeUnknown` / `onAccessBlocked` hooks (read by reflection — a real call would need Slack) makes the scrape show `codecompanion_slack_dispatch_outcome_unknown_total{method="chat.postMessage"} 1.0` and `codecompanion_slack_dispatch_access_blocked_total{error="invalid_auth"} 1.0`, the names the runbook alerts on. The application smoke test mocks the dispatcher, so this is the only spec that runs the configuration's lambdas |
| `SchedulingConfigTest.kt` | With `spring.threads.virtual.enabled=true` and `spring.task.scheduling.pool.size=4`, the `TaskScheduler` is the `ThreadPoolTaskScheduler` from `SchedulingConfig` with a core pool of 4. Without that bean Boot creates `SimpleAsyncTaskScheduler`, which ignores `pool.size` and runs every fixedDelay job on one thread |

## For AI Agents

### Working In This Directory
- Assert the configured pool through `scheduledThreadPoolExecutor.corePoolSize`; `ThreadPoolTaskScheduler.poolSize`
  reports live threads (0 before any task runs) once the scheduler is initialized.
- Keep these runners to the auto-configuration under test plus the one user configuration class; the full
  application context is not booted here.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.configurations.*'
```
