<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-01 | Updated: 2026-10-01 -->

# test/kotlin/dev/notypie/application/configurations

## Purpose
Specs for `src/main/kotlin/dev/notypie/application/configurations/` that need Spring to resolve which bean
wins: Boot auto-configuration plus one configuration class, started with `ApplicationContextRunner`.

## Key Files
| File | Description |
|------|-------------|
| `AppConfigSecretsTest.kt` | `requireUsableSecrets()`: a Slack token or sidecar secret that kept its `${...}` placeholder → `IllegalStateException` naming the key and never the token value; a blank Slack token → failure; a set token with blank optional secrets → passes |
| `KafkaProducerConfigurationTest.kt` | `KafkaAutoConfiguration` + `KafkaEventPublisherConfiguration` with `event-publisher=KAFKA`: the `KafkaTemplate` uses the `ProducerFactory` bean (same instance), and `spring.kafka.producer.acks` and a `spring.kafka.properties.*` key reach the producer configuration. No broker is contacted |
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
