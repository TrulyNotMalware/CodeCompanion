<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-08 -->

# test/kotlin/dev/notypie/application/service

## Purpose
One spec directory per use-case lane in `application/service`. Every spec mocks the ports (`OutboundMessageStager`,
`EventPublisher`, `OutboundMessagePort`, repositories, `AgentGateway`), injects a fixed `Clock` where time
matters, and asserts on the captured `OutboundMessage` effects — never on Slack payloads.

## Key Files
| File | Description |
|------|-------------|
| `OutboxPayloadSizeGuardTest.kt` | Stages the largest chain heads the bot produces through the real services and `CodecOutboundMessagePort`, and measures the CDC update record as Debezium writes it: the payload re-encoded as a JSON string (escapes included), twice (before / after), plus a 32 KiB envelope reserve, against Kafka's default 1 MiB. AI answers of 300,000 Korean characters, control characters and backslashes (cut at `MAX_ANSWER_LENGTH`; the Korean one is over `TEXT` and within `MEDIUMTEXT`); a 30-member standup summary at the modal answer cap with Korean answers, backslash-and-quote answers, and eight 199-character control-character questions with backslash answers (all several parts); control-character answers, stripped by `boundedForSummary`; one user's 500-event digest day with 128 / 512 / 700-character topic names, titles and summaries of Korean text, backslashes, control characters and ampersands (escaping widens them fivefold). The largest record is about 735 KB (summary, backslashes and quotes) |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `agent/` | `AgentConverseService` one-turn flow (see `agent/AGENTS.md`) |
| `command/` | `CommandExecutor`, `CommandRoleResolver`, `RoleManagementService` (see `command/AGENTS.md`) |
| `cve/` | CVE bootstrap plus `ai/`, `collector/`, `notification/`, `ops/`, `query/`, `subscription/` (see `cve/AGENTS.md`) |
| `interaction/` | `SlackInteractionHandlerImpl` routing (see `interaction/AGENTS.md`) |
| `meeting/` | Agenda/reminder schedulers, reschedule, `MeetingServiceImpl` listeners and their Google Calendar mirror hooks (see `meeting/AGENTS.md`) |
| `mention/` | `SlackMentionEventHandlerImpl` payload parsing (see `mention/AGENTS.md`) |
| `ops/` | `OpsStatusService` report rendering (see `ops/AGENTS.md`) |
| `relay/` | Outbox renderer, polling processor, relay service (see `relay/AGENTS.md`) |
| `standup/` | Routine setup, answers, scheduling, summary, message builders (see `standup/AGENTS.md`) |
| `calendar/` | `CalendarConnectionService` actions and callback outcomes, the token revocation worker (unit and real-H2 after-commit specs), disabled responder, and the Google Calendar mirror: hook queueing (`MeetingCalendarMirrorServiceTest`), the access-token cache (`GoogleAccessTokenProviderTest`), the sync worker and its scheduler (`CalendarSyncServiceTest`) (see `calendar/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
