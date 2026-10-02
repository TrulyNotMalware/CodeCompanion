<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-02 -->

# test/kotlin/dev/notypie/application/service

## Purpose
One spec directory per use-case lane in `application/service`. Every spec mocks the ports (`OutboundMessageStager`,
`EventPublisher`, `OutboundMessagePort`, repositories, `AgentGateway`), injects a fixed `Clock` where time
matters, and asserts on the captured `OutboundMessage` effects — never on Slack payloads.

## Key Files
| File | Description |
|------|-------------|
| `OutboxPayloadSizeGuardTest.kt` | Cross-lane guard (review H1). Drives the real `AgentConverseService` (300,000-character Korean answer; 300,000 C0 control characters; 300,000 backslashes) and `StandupSummaryService` (30 members, one one-character question, every answer at the `max_length` the real `ModalTemplateBuilder` modal hands out, in Korean) with MockK ports, encodes what they stage with the real `CodecOutboundMessagePort`, and asserts the UTF-8 payload is over TEXT (65,535) yet within MEDIUMTEXT (16,777,215), and that the CDC update record — the payload re-escaped as a JSON string column, twice (before and after), plus a 32 KiB envelope reserve — fits Kafka's default 1 MiB `max.request.size` for every input, the escape-heavy ones included; at the renderer's old 139,200-character cap the control-character and backslash records overflowed (Codex round 2) |

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
