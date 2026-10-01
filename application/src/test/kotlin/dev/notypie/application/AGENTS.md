<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-01 -->

# test/kotlin/dev/notypie/application

## Purpose
Root package of the module's specs. Each subpackage tests the production package of the same name under
`src/main/kotlin/dev/notypie/application/`. Run everything with `./gradlew :application:test`, or one
package with `./gradlew :application:test --tests 'dev.notypie.application.<package>.*'`.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `common/` | `IdempotencyCreator` / `DefaultIdempotencyDataSerializer` (see `common/AGENTS.md`) |
| `configurations/` | Bean selection that needs a Spring context: the `TaskScheduler` under virtual threads (see `configurations/AGENTS.md`) |
| `exception/` | `ControllerAdvice` handler responses (see `exception/AGENTS.md`) |
| `health/` | `OutboxHealthIndicator` actuator contributor (see `health/AGENTS.md`) |
| `mcp/` | `McpToolGate` and `DomainReadTools` MCP tools (see `mcp/AGENTS.md`) |
| `security/` | Slack signature filter, retry dedup, cached body wrapper, MCP token codec (see `security/AGENTS.md`) |
| `service/` | One spec directory per use-case lane (see `service/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
