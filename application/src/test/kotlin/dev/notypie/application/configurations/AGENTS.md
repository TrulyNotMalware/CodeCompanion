<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-01 | Updated: 2026-10-02 -->

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
| `ShutdownBudgetTest.kt` | Reads `application-prod.yaml` (`timeout-per-shutdown-phase`, agent `shutdown-await-seconds`) and the `Deployment` in `k8s/deployment.yaml` (`terminationGracePeriodSeconds`, the `preStop` sleep) and asserts the grace period covers preStop + 3 phases (scheduler with a running job, Kafka containers, web server drain) + the relay, agent-turn and default executor waits + 3 Kafka producer closes (`PRODUCER_CLOSE_TIMEOUT_SECONDS` each: the app's JSON producer and the two dead-letter producers). Fails at 90s, at 80s and at the old 45s grace. A second case closes a lazy-init `AnnotationConfigApplicationContext` holding `AsyncConfig`, `AgentConfiguration` and a probe bean named `entityManagerFactory` created after both executors: the relay and agent-turn executors are already shut down when the probe is destroyed (`@DependsOn`) |
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
