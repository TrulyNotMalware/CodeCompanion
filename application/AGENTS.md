<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-10-01 -->

# application

## Purpose
The Spring Boot bootstrap and use-case orchestration layer. It owns the HTTP surface (Slack events,
slash commands, interactivity), the Socket Mode alternative for local runs, request authentication,
the scheduled jobs, the outbox relay drivers, the MCP tool server, and every service that composes
`domain` behaviour with `infrastructure` adapters.

This is the only module that produces a runnable `bootJar`. It depends on both `:domain` and
`:infrastructure`.

## Key Files
| File | Description |
|------|-------------|
| `build.gradle.kts` | Spring Boot BOM, Jetty (Tomcat excluded), Actuator with the Micrometer Prometheus registry (`/actuator/prometheus` in `dev`/`prod`), AOP/AspectJ, Spring AI MCP server (declared as its four modules, not the `spring-ai-starter-mcp-server-webmvc` starter, which re-imports `starter-web` and leaks Tomcat past the exclude), Slack Socket Mode client + tyrus, `-PjarName=` override for `bootJar` |
| `src/main/kotlin/dev/notypie/CodeCompanion.kt` | `@SpringBootApplication @ConfigurationPropertiesScan` entry point and `main()` |
| `Dockerfile` | `eclipse-temurin:25.0.4_7-jre-alpine`; copies `build/libs/$JAR_FILE_NAME.jar`, runs `java -XX:MaxRAMPercentage=50.0 -Dspring.profiles.active=$PROFILE -Duser.timezone=Asia/Seoul -jar /app.jar` (heap = 50% of the k8s memory limit; the Pod's memory request covers heap plus non-heap). Runs as root because it binds port 80 (open item in `src/main/resources/k8s/AGENTS.md`). Build context for the deploy workflow |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `src/main/kotlin/dev/notypie/application/controllers/` | `SlackEventController` (`/api/slack/events`, `/interaction`) and `SlashCommandController` (`/api/slash/*`) (see `src/main/kotlin/dev/notypie/application/controllers/AGENTS.md`) |
| `src/main/kotlin/dev/notypie/application/service/` | Use-case services (see `src/main/kotlin/dev/notypie/application/service/AGENTS.md`) |
| `src/main/kotlin/dev/notypie/application/security/` | Slack signature verification, retry dedup, MCP turn tokens (see `src/main/kotlin/dev/notypie/application/security/AGENTS.md`) |
| `src/main/kotlin/dev/notypie/application/configurations/` | Bean wiring, conditions, Kafka/async/scheduling config (see `src/main/kotlin/dev/notypie/application/configurations/AGENTS.md`) |
| `src/main/kotlin/dev/notypie/application/mcp/` | `McpToolGate` + `DomainReadTools` — role-gated MCP tools exposed to the AI agent |
| `src/main/kotlin/dev/notypie/application/socket/` | `SocketModeReceiver` — local-only WebSocket inbound transport (`local` profile) |
| `src/main/kotlin/dev/notypie/application/health/` | `OutboxHealthIndicator` (Actuator health: pending lag and stuck rows) and `OutboxMetrics` (Prometheus gauges for the same numbers) (see `src/main/kotlin/dev/notypie/application/health/AGENTS.md`) |
| `src/main/kotlin/dev/notypie/application/exception/` | `ControllerAdvice`, `PayloadParseException` (see `src/main/kotlin/dev/notypie/application/exception/AGENTS.md`) |
| `src/main/kotlin/dev/notypie/application/common/` | `SlackRequestParser`, `IdempotencyCreator`, `TransactionTemplateExt` (see `src/main/kotlin/dev/notypie/application/common/AGENTS.md`) |
| `src/main/resources/` | Profile YAML, hand-applied SQL migrations, k8s and CDC manifests (see `src/main/resources/AGENTS.md`) |
| `src/` | Module sources: main, test, testFixtures (see `src/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **Two inbound transports, one handler set.** `SlackEventController` / `SlashCommandController` (HTTP)
  and `SocketModeReceiver` (WebSocket, `local` profile only) both call the *same*
  `AppMentionEventHandler` / `InteractionHandler` / `*SlashService` beans. When you add a slash command
  or event type, wire it into **both** or local testing silently diverges from production.
- `SlackEventController` acknowledges every non-`app_mention` event with a bare `200` on purpose:
  Slack fans many event types into one webhook and some payloads lack `event.user`, which would trip
  strict Kotlin deserialization. Do not "fix" this by widening deserialization.
- The interaction endpoint returns an empty `200` for a normal ack; a **non-null** body is a
  `view_submission` `response_action` (e.g. inline modal validation errors).
- Slow external calls (LLM turns, Slack Web API) must never run on the inbound request thread or hold
  a transaction. The AI turn runs in an async application listener for exactly this reason.
- Beans are mostly declared explicitly in `configurations/` rather than via component scanning of
  services — follow the existing style when adding a service.

### Testing Requirements
```bash
./gradlew :application:test
```
Specs mirror the package layout under `src/test/kotlin/`. Most are plain Kotest + MockK unit specs. The one
`@SpringBootTest` is `ApplicationContextSmokeTest`, which boots the whole application on H2 (polling relay,
in-process events, `MessageDispatcher` replaced by `@MockkBean`) and pins the wiring that unit specs cannot see:
the Hikari pool size, the `ThreadPoolTaskScheduler`, the `relayTaskExecutor` qualifier, the single `Clock`, the
outbox gauges in the Prometheus scrape, the AI turn running only after commit on the bounded `agent-turn-` executor
and the BEFORE_COMMIT outbox write. There is no `src/test/resources`.
Some other specs run against an in-memory H2 with a real
transaction manager, built from testFixtures: `outbox/OutboxJpaTestContext.kt` (`createOutboxJpaContext()`, a
small `AnnotationConfigApplicationContext` with `JpaTransactionManager`) and
`service/meeting/MeetingTransactionFixtures.kt` (`createH2DataSource`, `createH2TransactionManager`,
`createBoundedH2DataSource`). The `@DataJpaTest` / `EmbeddedKafka` slices live in `:infrastructure`. Shared builders live in
`src/testFixtures/kotlin/` (`OutboxTestFixtures`, `AppMentionPayloadCreator`, `AgendaItemCreator`,
`ScopedTurnTokenCreator`, `CveTopicConfigCreator`, ...) and `dev/notypie/docs/` holds the REST Docs DSL.
This module also consumes `testFixtures(project(":domain"))` and `testFixtures(project(":infrastructure"))`.

### Common Patterns
- Interface + `Impl` pairs for use cases (`MeetingService` / `MeetingServiceImpl`,
  `InteractionHandler` / `SlackInteractionHandlerImpl`, `MessageRelayService` / `SlackMessageRelayServiceImpl`).
  The interface is what other services and the socket receiver depend on.
- `@Scheduled` jobs are split into a thin `*Scheduler` (cron/fixed-rate trigger) and a `*SchedulingService`
  (the actual logic) so the logic is unit-testable without a scheduler.
- The scheduler pool is sized to 4 in `application.yaml` — Spring's single-thread default would let one
  slow job (e.g. the CVE collector's outbound HTTP) starve every other job. The size applies only because
  `SchedulingConfig` declares a `ThreadPoolTaskScheduler`: with virtual threads on, Boot's default is
  `SimpleAsyncTaskScheduler`, which ignores `pool.size` and runs every fixedDelay job on a single thread.
- `Clock` is a required constructor parameter of every time-dependent bean (no default), and `@Bean` factories pass
  the context's single `Clock` bean, so specs and the smoke test can pin time. `AppConfig` is likewise never defaulted
  in a bean constructor.
- Config binding via `@ConfigurationProperties` classes under `configurations/`, scanned by
  `@ConfigurationPropertiesScan` on the main class.

## Dependencies

### Internal
- `:domain` — command aggregate, intents, outbound messages, entities
- `:infrastructure` — Slack adapters, JPA repositories, Kafka publisher, AI sidecar client, templates

### External
Spring Boot (Web on Jetty, Actuator, AOP/AspectJ, DevTools), Micrometer Prometheus registry, Spring AI MCP server (WebMVC),
Slack `slack-api-client` + tyrus WebSocket, Jackson 3 Kotlin module, Spring REST Docs (test fixtures).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
