<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-30 -->

# infrastructure/src/testFixtures

## Purpose
Gradle `java-test-fixtures` source set for `:infrastructure`. It holds builder functions for types this module
owns — JPA schema rows, raw Slack payload JSON, typed parser output, infrastructure event classes — with
defaulted named parameters so a spec only states what matters to it. Consumed by this module's own specs and
by `:application` through `testImplementation(testFixtures(project(":infrastructure")))`; it in turn depends
on `testFixtures(project(":domain"))` for the shared `TEST_*` identity constants. House rule: any object, JSON
document, or DTO that takes more than a line or two to construct is built here as a `create*` / `*Json`
function, never inline in a spec.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `kotlin/` | Fixture sources, package-mirrored to `src/main/kotlin` (see `kotlin/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
