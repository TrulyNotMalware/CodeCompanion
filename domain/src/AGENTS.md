<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/src

## Purpose
The three Gradle source sets of the `:domain` module. `main/` is the framework-free domain code that
`:application` and `:infrastructure` compile against; `test/` holds the plain-JVM Kotest specs including
`DomainLayeringGuardTest`; `testFixtures/` is published through `java-test-fixtures` so the other two
modules reuse the same builders instead of inlining `Meeting(...)` / `Routine(...)` literals.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `main/` | Production sources — `dev.notypie.domain.{command,common,meet,standup}` (see `main/AGENTS.md`) |
| `test/` | Kotest specs mirroring the main packages plus the architecture guard (see `test/AGENTS.md`) |
| `testFixtures/` | Shared factories (`createMeeting`, `createRoutine`, `InboundCommandCreator`, ...) consumed by all three modules' tests (see `testFixtures/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
