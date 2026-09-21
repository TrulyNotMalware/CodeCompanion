<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/src/main/kotlin/dev/notypie

## Purpose
Package root of the infrastructure module, split into six packages: `impl` (Slack transport, AI sidecar,
CVE feed, retry adapters), `repository` (JPA lanes plus the transactional outbox), `templates` (Slack Block
Kit builders), `configurations` (`JpaConfiguration`, `RetryConfiguration`), `exception` (`ErrorBroadcaster`,
`DatabaseException`), and `common` (`jsonMapper`, `JPAJsonConverter`, `PartitionKeyUtil`). No source file
sits directly in this package.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `common/` | Shared Jackson 3 `jsonMapper`, `JPAJsonConverter`, `PartitionKeyUtil` (see `common/AGENTS.md`) |
| `configurations/` | HikariCP/JPA wiring and the thirteen repository-adapter beans; `RetryTemplate` + `RetryService` (see `configurations/AGENTS.md`) |
| `exception/` | `ErrorBroadcaster` port and implementations; `meeting/DatabaseException` family (see `exception/AGENTS.md`) |
| `impl/` | Transport and external-service adapters: `command/` (Slack), `agent/`, `cve/`, `retry/` (see `impl/AGENTS.md`) |
| `repository/` | Spring Data JPA repositories, `*RepositoryImpl` mappers, `schema/` entities, outbox (see `repository/AGENTS.md`) |
| `templates/` | Slack message/modal templates and the `SlackViewDsl` / `LayoutBlocksDsl` builders (see `templates/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
