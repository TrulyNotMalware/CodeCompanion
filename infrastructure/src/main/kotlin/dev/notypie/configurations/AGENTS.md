<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-04-28 | Updated: 2026-08-26 -->

# infrastructure/configurations

## Purpose
The two Spring `@Configuration` classes owned by the infrastructure module: `JpaConfiguration` (HikariCP
datasource, `@Primary` lazy-connection proxy, JPA repository scanning, and the thirteen `@Bean @Primary`
repository-adapter factories) and `RetryConfiguration` (a `RetryTemplate`, the `RetryService` bean, and
the `RetryOptions` defaults enum). Slack dispatch, Kafka, the AI-agent lane, CVE, MCP, and scheduling are
wired in `application/src/main/kotlin/dev/notypie/application/configurations/`; this package covers
persistence and retry only.

## Key Files
| File | Description |
|------|-------------|
| `JpaConfiguration.kt` | `@EnableJpaRepositories(basePackages = [JPA_ENTITY_PACKAGES])` with `JPA_ENTITY_PACKAGES = "dev.notypie.repository"`. `hikariDataSource(DataSourceProperties)` builds a `HikariDataSource`; `lazyConnectionDataSourceProxy` wraps it as the `@Primary` `DataSource`. Then one `@Bean @Primary` factory per adapter: `meetingRepository`, `meetingReminderRepository`, `agendaDispatchRepository`, `agentSessionRepository`, `agentTurnHistoryRepository`, `userCommandRoleRepository`, `mcpToolCallHistoryRepository`, `cveTopicRepository`, `cveEventRepository`, `cveSubscriptionRepository`, `cveCollectLedgerRepository`, `cveDeliveryRepository`, `standupRepository`. Also declares `PRIMARY_DATASOURCE_CONFIG = "primaryPersistenceUnit"`, which nothing references |
| `RetryConfiguration.kt` | `@EnableResilientMethods @Configuration`. `retryTemplate()` is `@ConditionalOnMissingBean(RetryTemplate::class)` and seeds a `RetryPolicy` from the `RetryOptions` defaults with `includes(listOf(Exception::class.java))`; `retryService(retryTemplate)` returns `RetryService(retryTemplate = ...)`. The same file defines `enum class RetryOptions(internal val default: Long)`: `MAX_ATTEMPTS = 3`, `INITIAL_DELAY = 100`, `MULTIPLIER = 2`, `MAX_DELAY = 10000`, `JITTER = 10` (milliseconds) |

## For AI Agents

### Working In This Directory
- **`JpaConfiguration` is the canonical place to register a repository adapter.** The port interfaces
  (`MeetingRepository`, `StandupRepository`, `CveTopicRepository`, ...) and their `*Impl` classes both
  live under `infrastructure/repository/<lane>/`; `:application` injects the interface type and `@Primary`
  is what lets it do so without a qualifier. Declare the factory here as an explicit `@Bean` with named
  arguments rather than putting `@Component` on the `*Impl` — every adapter today follows that shape and
  none carries a stereotype annotation.
- **Every adapter is registered unconditionally**, including the five CVE lanes. `OpsStatusService` in
  `:application` relies on this (its CVE repositories are always injectable even when `cve.enabled` is
  false), so do not gate a factory here on a feature flag; feature gating belongs in the application
  module's `CveConfiguration` / `AgentConfiguration` / `McpServerConfiguration`.
- **`LazyConnectionDataSourceProxy` must stay the `@Primary` `DataSource`.** It defers physical
  connection acquisition until the first statement, so transactions that never touch the database do not
  hold a pooled connection. The raw `HikariDataSource` is still a (non-primary) bean. Pool sizing and
  isolation are not set here — they come from `spring.datasource.hikari.*` in the profile YAMLs
  (`maximum-pool-size: 10`, `TRANSACTION_REPEATABLE_READ`, ...).
- **There is no `@EnableJpaAuditing` and no `@EntityScan`.** Entity discovery relies on the
  `@SpringBootApplication` root package (`dev.notypie`) covering `dev.notypie.repository`; the explicit
  `@EnableJpaRepositories` makes Boot's `JpaRepositoriesAutoConfiguration` back off in the full context.
- **`RetryService.execute` rebuilds a `RetryPolicy` per call and assigns it to the shared `RetryTemplate`**
  (`retryTemplate.retryPolicy = policy`) before running. Consequences: the defaults configured in
  `retryTemplate()` are overwritten on the first call, per-call overrides (`SlackMessageRelayServiceImpl`
  passes `maxAttempts = 5`) are visible to concurrent callers of the singleton, and the
  `recoveryCallBack` runs only after a `RetryException`. `RetryOptions.default` is `internal`, so
  `:application` callers cannot read the defaults — they pass named overrides to `execute` instead.
- **`@EnableResilientMethods` is on but unused.** No `@Retryable` / `@ConcurrencyLimit` method exists in
  the repository; all retry goes through `RetryService` (`impl/command/ApplicationMessageDispatcher`,
  application `MeetingServiceImpl`, `SlackMessageRelayServiceImpl`).
- Keep infrastructure configurations in this package; application-layer configs belong in the
  `application` module.

### Testing Requirements
```bash
./gradlew :infrastructure:test
```
No spec targets this package directly. `@DataJpaTest` slices under `repository/` filter out user
`@Configuration` classes, so they run on Boot's auto-configured H2 datasource and repository scanning, not
on `JpaConfiguration`; the `*RepositoryImplTest` specs construct adapters by hand with MockK'd `Jpa*`
repositories. The only test that boots this package is `impl/command/KafkaEventPublisherTest`
(`@SpringBootTest` via `TestApplication.kt`, H2 + `EmbeddedKafka`) — if a new `@Bean` here fails to wire,
that is the spec that breaks. `RetryService` itself is covered by `impl/retry/RetryServiceTest`, which
uses a bare `RetryTemplate()` rather than this configuration.

### Common Patterns
- `@Bean` factories use named arguments for every constructor parameter and let Kotlin infer the concrete
  `*Impl` return type.
- `@Primary` on every adapter bean and on the datasource proxy, so injection by interface type is
  unambiguous.
- `@ConditionalOnMissingBean` only on `retryTemplate()`; it is evaluated when this class is processed, so a
  custom `RetryTemplate` wins only if its definition is registered first.

## Dependencies

### Internal
- `repository/meeting` — `JpaMeetingRepository`, `JpaMeetingReminderRepository`,
  `JpaAgendaDispatchRepository`, `MeetingRepositoryImpl`, `MeetingReminderRepositoryImpl`,
  `AgendaDispatchRepositoryImpl`
- `repository/standup` — `JpaRoutineRepository`, `JpaStandupSessionRepository`,
  `JpaSessionDispatchRepository`, `StandupRepositoryImpl`
- `repository/agent` — `JpaAgentSessionRepository`, `JpaAgentTurnHistoryRepository`,
  `AgentSessionRepositoryImpl`, `AgentTurnHistoryRepositoryImpl`
- `repository/authorization` — `JpaUserCommandRoleRepository`, `UserCommandRoleRepositoryImpl`
- `repository/mcp` — `JpaMcpToolCallHistoryRepository`, `McpToolCallHistoryRepositoryImpl`
- `repository/cve` — `JpaCveTopicRepository`, `JpaCveEventRepository`, `JpaCveSubscriptionRepository`,
  `JpaCveCollectLedgerRepository`, `JpaCveDeliveryRepository` and their five `*RepositoryImpl` classes
- `impl/retry` — `RetryService` (which in turn reads `RetryOptions` from this package)

### External
HikariCP (`com.zaxxer.hikari.HikariDataSource`), Spring Boot `DataSourceProperties`
(`org.springframework.boot.jdbc.autoconfigure`) and `@ConditionalOnMissingBean`, Spring JDBC
`LazyConnectionDataSourceProxy`, Spring Data JPA `@EnableJpaRepositories`, Spring Framework 7 core retry
(`org.springframework.core.retry.RetryTemplate` / `RetryPolicy`) and resilience
(`org.springframework.resilience.annotation.EnableResilientMethods`) — `spring-retry` is no longer on the
classpath.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
