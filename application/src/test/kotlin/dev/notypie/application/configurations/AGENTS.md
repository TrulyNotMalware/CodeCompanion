<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-09-30 | Updated: 2026-10-01 -->

# test/kotlin/dev/notypie/application/configurations

## Purpose
Spring wiring smoke tests for `src/main/kotlin/dev/notypie/application/configurations/`. The behaviour specs of
each bean cannot see which implementation Boot actually registers; these start a real (small) application
context and assert the bean Boot ends up with.

## Key Files
| File | Description |
|------|-------------|
| `SchedulingWiringSmokeTest.kt` | `ApplicationContextRunner` with `ConfigDataApplicationContextInitializer` (so the real `application.yaml` is loaded), `TaskSchedulingAutoConfiguration`, `SchedulingConfig`, `AsyncConfig`, an `AppConfig()` bean and `spring.threads.virtual.enabled=true`. Asserts the `taskScheduler` bean is a `ThreadPoolTaskScheduler` with core pool ≥ 2; that a fixed-delay job blocked on a latch does not stop a second fixed-delay job from running within 2 s; and that `relayTaskExecutor` is a `ThreadPoolTaskExecutor` with `AbortPolicy` and queue = `outbox.polling.batch-size`. All three failed before review T1 was fixed (Boot chose `SimpleAsyncTaskScheduler`, the second job never ran, the policy was `CallerRunsPolicy`). Review F2/F4: a real `SlackMessageRelayServiceImpl` (`createRelayService`) over the real `relayTaskExecutor` bean with its threads prestarted hands out exactly `queueCapacity` slots, and once the four threads hold claims exactly `maxPoolSize` more; all 104 claims are dispatched, none rejected |

| `ShutdownBudgetTest.kt` | Review T12. The `concurrentKafkaListenerContainerFactory` has `isStopImmediate = true` and `shutdownTimeout = CDC_LISTENER_SHUTDOWN_TIMEOUT` (≥ 60 s); `relayTaskExecutor`'s `awaitTerminationMillis` (read with `ReflectionTestUtils`) is at least that; for `local`, `dev` and `prod` the resolved `spring.lifecycle.timeout-per-shutdown-phase` is at least that (an `ApplicationContextRunner` with the real config files); `k8s/deployment.yaml` (classpath, multi-document, the `Deployment`) has `terminationGracePeriodSeconds` ≥ the `preStop` sleep + 60 + 15. All six failed before the fix (`stopImmediate` false, 30 s await, 30 s / 30 s / 10 s phases, grace 45) |

## For AI Agents

### Working In This Directory
- Keep these runners small: register only the configuration classes and auto-configurations the assertion
  needs. A full `@SpringBootTest` would need Slack, Kafka and a datasource for no extra signal here.
- Always set `spring.threads.virtual.enabled=true`: every shipped profile does, and it is the switch that changes
  which scheduler Boot picks.
- The latch-based case must release its latch in `finally`, or the blocked scheduler thread outlives the context.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.configurations.*'
```

### Common Patterns
- `runner.run { context -> ... }` per `then`, `shouldBeInstanceOf<T>()` on the bean fetched by name.

## Dependencies

### Internal
- `application/configurations/SchedulingConfig.kt`, `AsyncConfig.kt`, `AppConfig.kt`
- `application/src/main/resources/application.yaml` — `spring.task.scheduling.pool.size`

### External
Spring Boot test (`ApplicationContextRunner`, `ConfigDataApplicationContextInitializer`), Boot task
auto-configuration, Kotest.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
