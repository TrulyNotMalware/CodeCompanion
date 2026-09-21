<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-30 -->

# infrastructure/src/test/kotlin/dev/notypie/impl

## Purpose
Specs for the outbound/inbound adapters in main `impl/`. No files at this level. Three of the four lanes are
pure unit specs; `command/` also carries the module's only `@SpringBootTest` (`KafkaEventPublisherTest`) and
the `view_submission` routing regression guard.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `agent/` | `SidecarAgentClient` SSE client against a loopback `HttpServer` (see `agent/AGENTS.md`) |
| `command/` | Slack parser / mapper / renderer / stager / intent resolver, Kafka publisher, REST requester; `slack/` sub-lane (see `command/AGENTS.md`) |
| `cve/` | GitHub-release and NVD source adapters against a loopback `HttpServer`, shared JSON helpers (see `cve/AGENTS.md`) |
| `retry/` | `RetryService` over Spring's core `RetryTemplate` (see `retry/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
