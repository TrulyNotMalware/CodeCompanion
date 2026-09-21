<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-21 -->

# db

## Purpose
Container for the versioned SQL patch set. The layout (`db/migration/V<n>__*.sql`) uses versioned-migration
naming, but no migration tool (Flyway included) is installed in any module, so the scripts are applied by hand against
environments that run `ddl-auto: none`; see `migration/AGENTS.md` for the rules.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `migration/` | `V1__` … `V17__` MariaDB patch scripts, one per schema change (see `migration/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
