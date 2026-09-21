<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# application/src/main/kotlin/dev/notypie

## Purpose
Package `dev.notypie`: the Spring Boot entry point and, beneath it, the whole `application` package
tree. `CodeCompanion.kt` is the only file at this level. Its package placement is what makes
`@SpringBootApplication` component-scan `dev.notypie.**` across all three Gradle modules — `:domain`
(`dev.notypie.domain.*`), `:infrastructure` (`dev.notypie.impl.*`, `dev.notypie.repository.*`,
`dev.notypie.configurations.*`) and this one share the root package.

## Key Files
| File | Description |
|------|-------------|
| `CodeCompanion.kt` | `@ConfigurationPropertiesScan @SpringBootApplication class CodeCompanion` (empty body) plus the top-level `main(args)` that calls `runApplication<CodeCompanion>(*args)`. All bean wiring lives in `application/configurations/` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `application/` | Package `dev.notypie.application` — controllers, services, security, configuration, MCP, socket, health (see `application/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- The scan root is this package, not `dev.notypie.application`. Moving `CodeCompanion.kt` deeper would
  stop the `@Service` / `@Configuration` classes in `:infrastructure` from being discovered.
- `@ConfigurationPropertiesScan` is the only thing that registers `AppConfig` (`slack.app`); there is
  no `@EnableConfigurationProperties` anywhere. A new `@ConfigurationProperties` class just needs to
  sit under `dev.notypie`.
- Keep the class body empty. Cross-cutting enablers (`@EnableAsync`, `@EnableScheduling`,
  `@EnableKafka`) live on dedicated classes in `application/configurations/` so they can be reasoned
  about and conditionally loaded; do not stack them here.
- The Docker entrypoint runs the boot jar with `-Dspring.profiles.active=$PROFILE
  -Duser.timezone=Asia/Seoul`; locally use `--spring.profiles.active=local`, which also enables the
  Socket Mode receiver in `application/socket/`.

### Testing Requirements
```bash
./gradlew :application:test
```
There is no Spring-context smoke test for the main class — every spec in this module is a plain Kotest
`BehaviorSpec` with MockK. A scan or property-binding regression only shows up at boot, so after
touching these annotations run the app with `--spring.profiles.active=local` and confirm it starts.

### Common Patterns
- One annotated, body-less application class plus a top-level `main` — the Kotlin Spring Boot idiom.
- Package-by-layer beneath `application/` (`controllers`, `service`, `security`, `configurations`, ...).

## Dependencies

### Internal
- `application/configurations/AppConfig` — bound through `@ConfigurationPropertiesScan`
- `:infrastructure`, `:domain` — component-scanned through the shared `dev.notypie` root package

### External
Spring Boot (`spring-boot-starter-web` on Jetty, `runApplication`).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
