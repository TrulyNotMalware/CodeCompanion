<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-01 -->

# application/src

## Purpose
Gradle source-set root of the `:application` module: production sources in `main/`, Kotest specs in
`test/`, and the `testFixtures/` source set (`java-test-fixtures`) that the specs build their inputs
from. No files live at this level.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `main/` | Production Kotlin sources plus profile YAML, migrations, and k8s/CDC manifests (see `main/AGENTS.md`) |
| `test/` | Kotest `BehaviorSpec` + MockK specs mirroring `main/kotlin`; a few start a small Spring context (`ApplicationContextRunner` wiring smoke tests, in-memory H2 transaction specs), none is a `@SpringBootTest` (see `test/AGENTS.md`) |
| `testFixtures/` | Fixture creators (`createOutboxRow`, `createScopedTurnToken`, ...) and the REST Docs DSL (see `testFixtures/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
