<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/src/test/resources

## Purpose
Classpath resources for the test set. Holds the single test-profile `application.yaml` that every
Spring-booting spec (`@DataJpaTest` repository specs, the `@SpringBootTest` Kafka publisher spec) loads.

## Key Files
| File | Description |
|------|-------------|
| `application.yaml` | Logging: `logging.level.root`, `logging.level.org.apache.kafka`, `logging.level.org.springframework.kafka`, `logging.level.kafka` (the last one exists to silence EmbeddedKafka's shutdown-race noise). Boot: `spring.main.banner-mode`. Kafka: `spring.kafka.producer.key-serializer`, `spring.kafka.producer.value-serializer`, `spring.kafka.consumer.group-id`, `spring.kafka.consumer.key-deserializer`, `spring.kafka.consumer.value-deserializer`, `spring.kafka.consumer.auto-offset-reset`, `spring.kafka.consumer.properties.spring.json.trusted.packages`. No datasource keys. |

## For AI Agents

### Working In This Directory
- No `spring.datasource.*` is set on purpose. `JpaConfiguration` builds its Hikari pool from
  `DataSourceProperties`, which resolves to the embedded H2 that `runtimeOnly("com.h2database:h2")` puts on
  the classpath; `@DataJpaTest` additionally swaps in its own embedded test database. Adding a URL here would
  point every repository spec at a real server.
- `spring.kafka.bootstrap-servers` is intentionally absent — `KafkaEventPublisherTest` injects it through
  `@EmbeddedKafka(bootstrapServersProperty = "spring.kafka.bootstrap-servers")`.
- The Kafka serializer classes are the Jackson 3 `JacksonJsonSerializer` / `JacksonJsonDeserializer` from
  `spring-kafka`; the classic `JsonSerializer` pair is Jackson 2 and would not match the module's
  `tools.jackson` mapper.
- Never put real tokens, hostnames, or credentials in this file. Live Slack values belong only in
  `:application`'s uncommitted local profile.

### Testing Requirements
Any change here is exercised by the Spring-booting specs:
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.*' --tests 'dev.notypie.impl.command.KafkaEventPublisherTest'
```

### Common Patterns
Per-spec settings go on the spec (`@EmbeddedKafka(...)`, `@DataJpaTest`), not in this file; it stays the
lowest common denominator for the whole test set.

## Dependencies

### Internal
- `../kotlin/dev/notypie/TestApplication.kt` — the `@SpringBootApplication` that loads this profile
- `infrastructure/src/main/kotlin/dev/notypie/configurations/JpaConfiguration.kt` — consumes the (absent)
  datasource properties

### External
Spring Boot test auto-configuration, spring-kafka + spring-kafka-test, H2.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
