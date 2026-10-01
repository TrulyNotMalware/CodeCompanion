<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-01 -->

# test

## Purpose
The `application` module's test source set. Most specs are plain Kotest `BehaviorSpec`s with MockK ports and
mirror the `src/main/kotlin` package layout one-to-one. The exceptions: `ApplicationContextSmokeTest` boots the
application context on H2, `configurations/` starts small `ApplicationContextRunner`s, and six specs use an
in-memory H2 with a real transaction manager built from testFixtures. Nothing here starts EmbeddedKafka.
Shared builders live in the sibling `testFixtures/` source set (see `../testFixtures/AGENTS.md`).

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `kotlin/` | Kotlin spec sources rooted at `dev.notypie.application` (see `kotlin/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
