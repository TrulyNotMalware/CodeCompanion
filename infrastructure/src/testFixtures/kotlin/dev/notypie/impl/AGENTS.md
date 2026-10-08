<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-08 -->

# infrastructure/src/testFixtures/kotlin/dev/notypie/impl

## Purpose
Package segment mirroring main `impl/`; no files. Only the `command/` and `calendar/` lanes have fixtures — the
`agent/`, `cve/` and `retry/` specs build their inputs from the JDK `HttpServer` stubs and `schema/` creators instead.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `command/` | Raw Slack interaction JSON builders, plus `event/` and `slack/` typed creators (see `command/AGENTS.md`) |
| `calendar/` | `CalendarEventBody` builder and Google API error JSON for `GoogleCalendarClientTest` (see `calendar/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
