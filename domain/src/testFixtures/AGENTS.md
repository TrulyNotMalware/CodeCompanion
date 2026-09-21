<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# domain/src/testFixtures

## Purpose
Gradle `java-test-fixtures` source set for `:domain` and the shared fixture layer of the whole build. It
holds the `TEST_*` identity constants, builders for transport-neutral inbound commands and interactions,
command request events, meeting and standup entities with their DTOs, and a `TestCommand` for driving the
executor. Every builder is a top-level `create*` function with defaulted named parameters that constructs a
**real** domain object — nothing here mocks.

Consumers: `domain/src/test`, plus `:infrastructure` and `:application`, which both declare
`testImplementation(testFixtures(project(":domain")))` and
`testFixturesImplementation(testFixtures(project(":domain")))` so their own fixtures default to the same
ids. The classpath is the domain's: Kotlin stdlib, `kotlin-reflect` and MockK from the root `subprojects`
block — no Spring, no Jackson.

House rule: any domain object that takes more than a line or two to construct is built here as a `create*`
function, never inline in a spec; the same builder then serves all three modules.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `kotlin/` | Fixture sources, package-mirrored to `src/main/kotlin` (see `kotlin/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
