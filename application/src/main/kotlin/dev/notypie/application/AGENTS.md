<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# application/src/main/kotlin/dev/notypie/application

## Purpose
Package `dev.notypie.application`, the module's package tree split by layer: inbound transports
(`controllers/`, `socket/`), request gates (`security/`), Spring wiring (`configurations/`), the MCP
tool surface (`mcp/`), Actuator health (`health/`), shared helpers (`common/`, `exception/`), and every
use case (`service/`). No sources sit at this level.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `common/` | `IdempotencyCreator`, `parseRequestBodyData`, `TransactionTemplate.runInTx` (see `common/AGENTS.md`) |
| `configurations/` | `AppConfig` tree, `Condition`s, and every `@Configuration` that declares beans (see `configurations/AGENTS.md`) |
| `controllers/` | `SlackEventController`, `SlashCommandController` HTTP entry points (see `controllers/AGENTS.md`) |
| `exception/` | `PayloadParseException` types and `ControllerAdvice` (see `exception/AGENTS.md`) |
| `health/` | `OutboxHealthIndicator` Actuator contribution (see `health/AGENTS.md`) |
| `mcp/` | `McpToolGate` and `DomainReadTools` — role-gated MCP tools (see `mcp/AGENTS.md`) |
| `security/` | Slack signature/retry gate and the MCP turn-token gate (see `security/AGENTS.md`) |
| `service/` | Use-case lanes: command, mention, interaction, meeting, standup, agent, cve, relay, ops (see `service/AGENTS.md`) |
| `socket/` | `SocketModeReceiver` — `local`-profile WebSocket inbound transport (see `socket/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
