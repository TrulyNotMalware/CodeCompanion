<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# application/src/testFixtures

## Purpose
Gradle `java-test-fixtures` source set for `:application`. It holds `create*` builders for types this
module owns or projects — `AppConfig.Cve.TopicDefinition`, a fully wired `PollingMessageProcessor`,
`ScopedTurnToken`, the AI `SummaryRequest`, agenda/reminder projections, standup nudge candidates and the
raw Slack `app_mention` event map — plus a Spring REST Docs field DSL under `docs/` that nothing consumes
yet. Every builder takes defaulted named parameters so a spec states only what matters to it.

Wiring in `application/build.gradle.kts`: `testFixturesImplementation(project(":infrastructure"))` for the
`dev.notypie.repository.*` projection types, `testFixturesImplementation(testFixtures(project(":domain")))`
for the `TEST_*` identity constants, `spring-restdocs-mockmvc` for `docs/`, and MockK from the root build.
Only this module's own `src/test` specs consume the set; no other module declares
`testFixtures(project(":application"))`.

House rule: any object, map, or DTO that takes more than a line or two to construct is built here as a
`create*` function, never inline in a spec.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `kotlin/` | Fixture sources, package-mirrored to `src/main/kotlin` plus the `dev.notypie.docs` DSL (see `kotlin/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
