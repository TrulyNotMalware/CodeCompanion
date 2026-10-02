<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-01 -->

# test

## Purpose
The `application` module's test source set. Every spec here is a plain Kotest spec with MockK ports — no
`@SpringBootTest` context, no EmbeddedKafka — and mirrors the `src/main/kotlin` package layout one-to-one. A few
start an in-memory H2 where real transaction behaviour is the point: `StandupSchedulingServiceTest` and
`DailyAgendaSchedulingServiceTest` (claims rolled back with a failed enqueue, on a `DataSourceTransactionManager`),
`RoleLookupJpaTransactionTest` and `MeetingWriteJpaTransactionTest` (Hibernate + `JpaTransactionManager`), all
built from `createH2DataSource` / `createH2MeetingJpaStore` in `../testFixtures/.../service/meeting/`.
Shared builders live in the sibling `testFixtures/` source set (see `../testFixtures/AGENTS.md`).

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `kotlin/` | Kotlin spec sources rooted at `dev.notypie.application` (see `kotlin/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
