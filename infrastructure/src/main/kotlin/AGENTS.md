<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/src/main/kotlin

## Purpose
Kotlin source root. Every file lives under `dev.notypie`, the same root package as `:domain` and
`:application`, which is what lets `:application`'s `@SpringBootApplication` scan discover
`dev.notypie.repository` entities without an `@EntityScan`.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `dev/` | Package segment `dev`; contains only `notypie/` (see `dev/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
