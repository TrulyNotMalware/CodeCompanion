<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# test/kotlin/dev/notypie/application/service

## Purpose
One spec directory per use-case lane in `application/service`. Every spec mocks the ports (`OutboundMessageStager`,
`EventPublisher`, `OutboundMessagePort`, repositories, `AgentGateway`), injects a fixed `Clock` where time
matters, and asserts on the captured `OutboundMessage` effects — never on Slack payloads.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `agent/` | `AgentConverseService` one-turn flow (see `agent/AGENTS.md`) |
| `command/` | `CommandExecutor`, `CommandRoleResolver`, `RoleManagementService` (see `command/AGENTS.md`) |
| `cve/` | CVE bootstrap plus `ai/`, `collector/`, `notification/`, `ops/`, `query/`, `subscription/` (see `cve/AGENTS.md`) |
| `interaction/` | `SlackInteractionHandlerImpl` routing (see `interaction/AGENTS.md`) |
| `meeting/` | Agenda/reminder schedulers, reschedule, `MeetingServiceImpl` listeners (see `meeting/AGENTS.md`) |
| `mention/` | `SlackMentionEventHandlerImpl` payload parsing (see `mention/AGENTS.md`) |
| `ops/` | `OpsStatusService` report rendering (see `ops/AGENTS.md`) |
| `relay/` | Outbox renderer, polling processor, relay service (see `relay/AGENTS.md`) |
| `standup/` | Routine setup, answers, scheduling, summary, message builders (see `standup/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
