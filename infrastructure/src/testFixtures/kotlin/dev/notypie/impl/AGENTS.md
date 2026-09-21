<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-30 -->

# infrastructure/src/testFixtures/kotlin/dev/notypie/impl

## Purpose
Package segment mirroring main `impl/`; no files. Only the `command/` lane has fixtures — the `agent/`, `cve/`
and `retry/` specs build their inputs from the JDK `HttpServer` stubs and `schema/` creators instead.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `command/` | Raw Slack interaction JSON builders, plus `event/` and `slack/` typed creators (see `command/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
