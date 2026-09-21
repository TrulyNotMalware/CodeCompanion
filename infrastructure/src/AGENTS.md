<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/src

## Purpose
Gradle source-set root of `:infrastructure`: production sources under `main/`, kotest specs under `test/`,
and the `testFixtures/` source set whose payload and schema creators are also consumed by `:application`.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `main/` | Production Kotlin sources — adapters, repositories, templates under `dev.notypie` (see `main/AGENTS.md`) |
| `test/` | H2 + `EmbeddedKafka` integration and unit specs, `TestApplication.kt`, `resources/application.yaml` (see `test/AGENTS.md`) |
| `testFixtures/` | Slack payload creators and schema creators reused by `:application` tests (see `testFixtures/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
