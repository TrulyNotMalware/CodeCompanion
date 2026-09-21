<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# test

## Purpose
The `application` module's test source set. Every spec here is a plain Kotest `BehaviorSpec` with MockK ports —
no Spring context, no EmbeddedKafka, no H2 — and mirrors the `src/main/kotlin` package layout one-to-one.
Shared builders live in the sibling `testFixtures/` source set (see `../testFixtures/AGENTS.md`).

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `kotlin/` | Kotlin spec sources rooted at `dev.notypie.application` (see `kotlin/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
