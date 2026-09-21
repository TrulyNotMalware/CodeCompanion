<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# application/src/testFixtures/kotlin/dev/notypie/application

## Purpose
Mirror of the `dev.notypie.application` main package. No files at this level; each subpackage builds the
inputs of the matching service, configuration or security component.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `configurations/` | `createCveTopicConfigDefinition` for `AppConfig.Cve.TopicDefinition` (see `configurations/AGENTS.md`) |
| `outbox/` | Fixed UTC clock, relaxed `OutboxMessage` rows, wired `PollingMessageProcessor`, outbox-status stubs (see `outbox/AGENTS.md`) |
| `security/` | `mcp/` — `createScopedTurnToken` (see `security/AGENTS.md`) |
| `service/` | Per-lane builders: `cve/ai`, `meeting`, `mention`, `standup` (see `service/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
