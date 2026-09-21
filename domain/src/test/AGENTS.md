<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-30 -->

# domain/src/test

## Purpose
The `test` source set of the `:domain` module: plain-JVM Kotest specs (no Spring context, no database,
no MockK usage) that pin the behaviour of the transport-neutral command core, the `meet` / `standup`
aggregates, the validation DSL, and the architecture guard that keeps the layer Slack- and Jackson-free.
Reusable input builders do not live here — they belong in the sibling `testFixtures` source set so
`:application` and `:infrastructure` can consume them too.

Run the whole set with `./gradlew :domain:test`; run one spec with
`./gradlew :domain:test --tests 'dev.notypie.domain.command.context.MeetingContextTest'`.
The Gradle `test` task runs with the module directory as the working directory, which the architecture
guard relies on (it reads `src/main/kotlin/...` relative to cwd).

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `kotlin/` | Kotlin sources — the only language in this source set (see `kotlin/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
