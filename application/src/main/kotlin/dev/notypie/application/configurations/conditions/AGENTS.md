<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# application/configurations/conditions

## Purpose
The custom Spring `Condition`s that pick a deployment mode at context-refresh time, plus the
`Environment.extractAppConfig()` helper they share. Each condition binds `slack.app` itself (via
`Binder`) because conditions are evaluated before any `@ConfigurationProperties` bean exists, then
compares one enum field of `AppConfig.Mode`. They are consumed only by the `@Conditional` configuration
classes in `../ConsumerConfig.kt`.

## Key Files
| File | Description |
|------|-------------|
| `Conditions.kt` | `Environment.extractAppConfig(): AppConfig` — `Binder.get(this).bind(APP_CONFIG_PROPERTIES_PREFIX, AppConfig::class.java).orElseGet { AppConfig() }`. Four `Condition`s whose `matches(context, metadata)` read `context.environment.extractAppConfig().mode`: `OnPollingConsumer` (`outboxReadingStrategy == OutboxReaderStrategy.POLLING`), `OnCdcConsumer` (`== CDC`), `OnKafkaEventPublisher` (`eventPublisher == EventPublisherType.KAFKA`), `OnApplicationEventPublisher` (`== APPLICATION_EVENT`) |

## For AI Agents

### Working In This Directory
- Consumers: `PoolingPublisherConfig` (`OnPollingConsumer`) and `CdcPublisherConfig` (`OnCdcConsumer`)
  choose the `MessageProcessor`; `KafkaEventPublisherConfig` (`OnKafkaEventPublisher`) and
  `ApplicationEventPublisherConfig` (`OnApplicationEventPublisher`) choose the `EventPublisher` and
  `ErrorBroadcaster`. The two pairs are independent axes — CDC + application events is a valid combo.
- Keep the `orElseGet { AppConfig() }` fallback: a profile with no `slack.app.mode` block must evaluate
  to the defaults (`POLLING`, `APPLICATION_EVENT`), not throw during condition evaluation.
- Every `matches` call re-binds the whole `AppConfig` tree. That is fine at startup (a handful of
  calls) but these classes are not for runtime checks — services get `AppConfig` injected.
- The KDoc on the classes still talks about a `PublisherType.POOLING` "publisher mode"; the real
  properties are `slack.app.mode.outbox-reading-strategy` (`OutboxReaderStrategy`) and
  `slack.app.mode.event-publisher` (`EventPublisherType`). Trust the code, not the comments.
- A new mode is a new `Condition` here plus a `@Conditional(OnXxx::class)` configuration in
  `../ConsumerConfig.kt`; do not add `if (config.mode == ...)` branches inside services.

### Testing Requirements
```bash
./gradlew :application:test
```
No spec covers this file today. When adding one, build a `MockEnvironment` with
`slack.app.mode.outbox-reading-strategy=cdc` (and the publisher property), wrap it in a MockK
`ConditionContext`, and assert both the matching and the non-matching branch of each condition, plus
the empty-environment default.

### Common Patterns
- Plain `class Xxx : Condition` with a single-expression `matches` override; no state, no logging.
- One condition per enum value rather than a parameterised condition, so `@Conditional` stays
  declarative and greppable.

## Dependencies

### Internal
- `application/configurations/AppConfig.kt` — `APP_CONFIG_PROPERTIES_PREFIX`, `AppConfig.Mode`,
  `OutboxReaderStrategy`, `EventPublisherType`
- `application/configurations/ConsumerConfig.kt` — the `@Conditional` consumers

### External
Spring Boot `Binder`, Spring `Condition` / `ConditionContext` / `AnnotatedTypeMetadata`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
