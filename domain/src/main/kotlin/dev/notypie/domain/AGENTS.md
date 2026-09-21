<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain (main)

## Purpose
Package root `dev.notypie.domain`. No sources at this level — four sub-packages with a one-way edge
enforced by `DomainLayeringGuardTest`: `command/` may import `common/`, `meet/`, `standup/`; none of
those three may import `command/`. The whole tree must stay free of Slack SDK, Jackson, and Gson
references and of the identifiers `responseUrl` / `triggerId` (see `domain/AGENTS.md`).

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `command/` | Transport-neutral command core: inbound envelopes, contexts, intents, outbound messages (see `command/AGENTS.md`) |
| `common/` | `validate {}` DSL, `IdempotencyData`, and the cross-module error contract (see `common/AGENTS.md`) |
| `meet/` | `Meeting` aggregate, `MeetingReminder`, and their read-model DTOs (see `meet/AGENTS.md`) |
| `standup/` | `Routine` / `StandupSession` aggregates, dispatch and answer records, DTOs (see `standup/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
