<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

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
| `build.gradle.kts` | Spring Boot BOM, Jetty (Tomcat excluded), Actuator, AOP/AspectJ, Spring AI MCP server, Slack Socket Mode client + tyrus, `-PjarName=` override for `bootJar` |
| `src/main/kotlin/dev/notypie/CodeCompanion.kt` | `@SpringBootApplication @ConfigurationPropertiesScan` entry point and `main()` |
| `Dockerfile` | `eclipse-temurin:25.0.1_8-jre-alpine`; copies `build/libs/$JAR_FILE_NAME.jar`, runs with `-Dspring.profiles.active=$PROFILE -Duser.timezone=Asia/Seoul`. Build context for the deploy workflow |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `src/main/kotlin/dev/notypie/application/controllers/` | `SlackEventController` (`/api/slack/events`, `/interaction`) and `SlashCommandController` (`/api/slash/*`) |
| `src/main/kotlin/dev/notypie/application/service/` | Use-case services (see `src/main/kotlin/dev/notypie/application/service/AGENTS.md`) |
| `src/main/kotlin/dev/notypie/application/security/` | Slack signature verification, retry dedup, MCP turn tokens (see `src/main/kotlin/dev/notypie/application/security/AGENTS.md`) |
| `src/main/kotlin/dev/notypie/application/configurations/` | Bean wiring, conditions, Kafka/async/scheduling config (see `src/main/kotlin/dev/notypie/application/configurations/AGENTS.md`) |
| `src/main/kotlin/dev/notypie/application/mcp/` | `McpToolGate` + `DomainReadTools` — role-gated MCP tools exposed to the AI agent |
| `src/main/kotlin/dev/notypie/application/socket/` | `SocketModeReceiver` — local-only WebSocket inbound transport (`local` profile) |
| `src/main/kotlin/dev/notypie/application/health/` | `OutboxHealthIndicator` — Actuator health contribution reporting pending lag and stuck rows |
| `src/main/kotlin/dev/notypie/application/exception/` | `ControllerAdvice`, `PayloadParseException` |
| `src/main/kotlin/dev/notypie/application/common/` | `SlackRequestParser`, `IdempotencyCreator`, `TransactionTemplateExt` |
| `src/main/resources/` | Profile YAML, Flyway-style migrations, k8s and CDC manifests (see `src/main/resources/AGENTS.md`) |

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
Specs mirror the package layout under `src/test/kotlin/`. Most are plain Kotest + MockK unit specs (no
Spring context); the outbox and Kafka paths use `EmbeddedKafka` and H2. Shared builders live in
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
  slow job (e.g. the CVE collector's outbound HTTP) starve every other job.
- `Clock` is injected into time-dependent services (default `Clock.systemDefaultZone()`) so specs can
  pin time.
- Config binding via `@ConfigurationProperties` classes under `configurations/`, scanned by
  `@ConfigurationPropertiesScan` on the main class.

## Dependencies

### Internal
- `:domain` — command aggregate, intents, outbound messages, entities
- `:infrastructure` — Slack adapters, JPA repositories, Kafka publisher, AI sidecar client, templates

### External
Spring Boot (Web on Jetty, Actuator, AOP/AspectJ, DevTools), Spring AI MCP server (WebMVC),
Slack `slack-api-client` + tyrus WebSocket, Jackson 3 Kotlin module, Spring REST Docs (test fixtures).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
