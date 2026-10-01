<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-01 | Updated: 2026-10-01 -->

# infrastructure/src/test/kotlin/dev/notypie/configurations

## Purpose
Specs for the wiring in main `configurations/` that only a Spring context can show: configuration properties
that must reach a bean built by a `@Bean` method.

## Key Files
| File | Description |
|------|-------------|
| `HikariDataSourceBindingTest.kt` | `@SpringBootTest` (via `TestApplication.kt`, embedded H2) with `spring.datasource.hikari.*` overrides; asserts that pool size, connection timeout, pool name and transaction isolation reach the `HikariDataSource` that `JpaConfiguration` builds. Fails if the bean method loses `@ConfigurationProperties("spring.datasource.hikari")` |

## For AI Agents

### Working In This Directory
- A unit test that constructs the bean by hand cannot see binding; keep these specs on a real context.
- Each distinct `properties` set starts its own context, so add new binding assertions to the existing spec
  when they can share its properties.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.configurations.*'
```
