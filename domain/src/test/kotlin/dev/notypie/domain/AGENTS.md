<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-30 -->

# domain (test)

## Purpose
Root package of the domain specs. The layout mirrors `domain/src/main/kotlin/dev/notypie/domain/`
package-for-package, with two deliberate exceptions: `command/context/` here covers both
`command/entity/context/` and `command/entity/context/form/` in main, and `command/parsers/` here covers
`command/entity/parsers/`. `architecture/` has no main counterpart — it is the layering guard.

Every spec is a Kotest `BehaviorSpec` (`given` / `when` / `then`) except the guard, which is a
`StringSpec`. Specs build input through the `testFixtures` creators (`createCommandBasicInfo`,
`createInboundInteraction`, `createIntentQueue`, `createMeeting`, `createRoutine`, ...) and assert on the
`IntentQueue` contents rather than on any transport call.

Run a package with `./gradlew :domain:test --tests 'dev.notypie.domain.command.context.*'`.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `architecture/` | `DomainLayeringGuardTest` — four source/classpath checks that keep the layer transport-free (see `architecture/AGENTS.md`) |
| `command/` | Command core: `CommandSet`, sub-command parsing, event queue, plus one sub-package per concern (see `command/AGENTS.md`) |
| `common/` | `ValidationBuilderTest` — the validation DSL used by every entity (see `common/AGENTS.md`) |
| `meet/` | `Meeting` / `Member` aggregate invariants (see `meet/AGENTS.md`) |
| `standup/` | `Routine`, `StandupSession`, `SessionDispatch`, `StandupAnswer` invariants (see `standup/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
