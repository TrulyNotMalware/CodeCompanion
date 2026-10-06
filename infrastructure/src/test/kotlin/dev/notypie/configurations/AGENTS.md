<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-01 | Updated: 2026-10-03 -->

# infrastructure/src/test/kotlin/dev/notypie/configurations

## Purpose
Specs for the wiring in main `configurations/` that only a Spring context can show: configuration properties
that must reach a bean built by a `@Bean` method.

## Key Files
| File | Description |
|------|-------------|
| `HikariDataSourceBindingTest.kt` | `@SpringBootTest` (via `TestApplication.kt`, embedded H2) with `spring.datasource.hikari.*` overrides; asserts that pool size, connection timeout, pool name and transaction isolation reach the `HikariDataSource` that `JpaConfiguration` builds. Fails if the bean method loses `@ConfigurationProperties("spring.datasource.hikari")` |
| `SnapshotIsolationExceptionTranslatorTest.kt` | `@SpringBootTest` (via `TestApplication.kt`, embedded H2). Unit cases: 1020 directly or as a cause → `SnapshotIsolationConflictException` that `isMeetingWriteConflict()`; a 1062 for `"Hibernate operation: "` → `null`; for `"Hibernate transaction: "` and a `JdbcTemplate` task → the same class `SQLExceptionSubclassTranslator` gives. `HibernateJpaDialect` without the translator turns `createRawSnapshotIsolationFailure` into `JpaSystemException`, with it into the conflict, while a Hibernate unique `ConstraintViolationException` stays exactly `DataIntegrityViolationException`. Context: the `&entityManagerFactory` `PersistenceExceptionTranslator` (what repository proxies use), the `JpaTransactionManager` dialect (commit path) and `JdbcTemplate` all use the bean — fails if it is missing or not unique. |

## For AI Agents

### Working In This Directory
- A unit test that constructs the bean by hand cannot see binding; keep these specs on a real context.
- Each distinct `properties` set starts its own context, so add new binding assertions to the existing spec
  when they can share its properties.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.configurations.*'
```
