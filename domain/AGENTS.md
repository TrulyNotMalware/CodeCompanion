<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-08-25 -->

# domain

## Purpose
The framework-free core of CodeCompanion. Pure Kotlin — **no Spring, no Jakarta, no JPA, no Jackson,
no Slack SDK** — and additionally **transport-agnostic**: `domain/command` contains zero Slack types.
Inbound requests arrive already normalized as `InboundCommand` / `InboundInteraction`, and results
leave as transport-neutral `CommandIntent`s and `OutboundMessage`s, so a second adapter (e.g. Discord)
can be added without touching this module.

This module produces a plain `jar` (`bootJar` disabled) and declares **no dependencies of its own** —
everything it compiles against comes from the root `subprojects` block (Kotlin reflect, kotlin-logging,
Kotest, MockK).

## Key Files
| File | Description |
|------|-------------|
| `build.gradle.kts` | Disables `bootJar`, enables plain `jar`, empty `dependencies` block (intentional) |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `src/main/kotlin/dev/notypie/domain/command/` | Transport-neutral command core (see `src/main/kotlin/dev/notypie/domain/command/AGENTS.md`) |
| `src/main/kotlin/dev/notypie/domain/meet/` | Meeting aggregate (see `src/main/kotlin/dev/notypie/domain/meet/AGENTS.md`) |
| `src/main/kotlin/dev/notypie/domain/standup/` | Standup routines, sessions, answers (see `src/main/kotlin/dev/notypie/domain/standup/AGENTS.md`) |
| `src/main/kotlin/dev/notypie/domain/common/` | `ValidationBuilder` DSL, `IdempotencyData`, shared error types |
| `src/test/kotlin/` | Kotest specs, including the architecture guard |
| `src/testFixtures/kotlin/` | Factories consumed by `application` and `infrastructure` tests |

## For AI Agents

### Working In This Directory
- **Hard constraint, enforced by tests.** `src/test/kotlin/dev/notypie/domain/architecture/DomainLayeringGuardTest.kt`
  fails the build if domain source contains any of:
  - `com.slack` references or `slack.com` URL literals
  - Jackson / Gson imports, or any `@Json*` annotation
  - Slack SDK payload types (`BlockActionPayload`, `ViewSubmissionPayload`, `SlashCommandPayload`,
    `SlackApiException`, `LayoutBlock`, `ViewState`)
  - the identifiers `responseUrl` or `triggerId` in code (comments describing Slack origin are fine —
    use neutral handle names like `replyHandle` / `triggerHandle` instead)
  - Jackson/Gson/Slack classes on the **test runtime classpath**, which catches accidental
    dependency injection from the root build even when no import exists
- `meet/`, `standup/`, and `common/` must **not** import `dev.notypie.domain.command` — the guard test
  enforces the one-way edge (`command` may depend on them, never the reverse). This prevents a
  previously removed package cycle from returning.
- Business identifiers `slackUserId` / `slackTeamId` and the `CommandDetailType` routing enum are
  known, intentionally-deferred leaks. They are **not** guarded; do not "fix" them as drive-by work.

### Testing Requirements
```bash
./gradlew :domain:test
```
Tests are plain JVM Kotest — no Spring context, no database, fast. When you add a new context, parser,
or entity invariant, add a matching `BehaviorSpec` under `src/test/kotlin/`. Reusable input builders
belong in `src/testFixtures/kotlin/` so the other two modules can reuse them.

### Common Patterns
- **Validation DSL** (`common/Utils.kt`): infix `ValidationBuilder` with `"field" of value and { ... }`,
  `or`, and `shouldNotBeNullAnd` chaining; collects every failure and throws one `ValidationException`.
- **Error details DSL** (`common/error/Errors.kt`): `exceptionDetails { "key" value v because "reason" }`.
- Sealed hierarchies everywhere for exhaustive `when` routing — `CommandIntent`, `OutboundMessage`,
  `InboundPayload`, `MessageContent`.
- `internal` visibility guards the command internals (`CommandContext`, `parseContext`,
  `findSubCommandDefinition`); only `Command`, its intents, and the neutral DTOs are public API.

## Dependencies

### Internal
None. `domain` is the bottom of the dependency graph — everything else depends on it.

### External
Only what the root build injects: `kotlin-reflect`, `io.github.oshai:kotlin-logging-jvm`, and (test-only)
Kotest + MockK. Adding anything else here is almost certainly a layering violation.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
