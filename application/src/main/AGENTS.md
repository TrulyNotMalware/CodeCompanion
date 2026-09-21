<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# application/src/main

## Purpose
Production half of the module. `kotlin/` holds every class under the `dev.notypie` package (the Spring
Boot entry point and the `dev.notypie.application` tree); `resources/` holds the `application*.yaml`
profiles that `AppConfig` binds from, `banner.txt`, the hand-applied `db/migration` SQL, and the k8s /
CDC deployment manifests.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `kotlin/` | Kotlin sources rooted at package `dev.notypie` (see `kotlin/AGENTS.md`) |
| `resources/` | Profile YAML, `banner.txt`, `db/migration`, `k8s/`, `cdc/` (see `resources/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
