<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-01 -->

# test/kotlin/dev/notypie/application/service

## Purpose
One spec directory per use-case lane in `application/service`. Every spec mocks the ports (`OutboundMessageStager`,
`EventPublisher`, `OutboundMessagePort`, repositories, `AgentGateway`), injects a fixed `Clock` where time
matters, and asserts on the captured `OutboundMessage` effects — never on Slack payloads.

## Key Files
| File | Description |
|------|-------------|
| `OutboxPayloadSizeGuardTest.kt` | Cross-lane guard (review H1). Drives the real `AgentConverseService` (300,000-character Korean answer; 300,000 C0 control characters) and `StandupSummaryService` (30 members, one one-character question, every answer at the `max_length` the real `ModalTemplateBuilder` modal hands out, in Korean) with MockK ports, encodes what they stage with the real `CodecOutboundMessagePort`, and asserts the UTF-8 payload is over TEXT (65,535) yet within MEDIUMTEXT (16,777,215), and — for the realistic inputs — that two copies plus a 32 KiB envelope reserve fit Kafka's default 1 MiB `max.request.size` (a Debezium update event carries the row before and after) |

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
