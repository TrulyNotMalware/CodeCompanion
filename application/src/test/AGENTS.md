<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-01 -->

# test

## Purpose
The `application` module's test source set, mirroring the `src/main/kotlin` package layout one-to-one. Most specs
are plain Kotest `BehaviorSpec`s with MockK ports. There is no `@SpringBootTest` and no EmbeddedKafka, but some specs
start a small Spring context on purpose: the `configurations/` wiring smoke tests use `ApplicationContextRunner`
(the real `application*.yaml`, a few configuration classes). A few start an in-memory H2 where real transaction
behaviour is the point: `StandupSchedulingServiceTest` and `DailyAgendaSchedulingServiceTest` (claims rolled back
with a failed enqueue, on a `DataSourceTransactionManager`), `RoleLookupJpaTransactionTest`,
`MeetingWriteJpaTransactionTest` and the outbox relay scenario specs (Hibernate + `JpaTransactionManager`), all built
from the H2 helpers in `../testFixtures/`.
Shared builders live in the sibling `testFixtures/` source set (see `../testFixtures/AGENTS.md`).

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `kotlin/` | Kotlin spec sources rooted at `dev.notypie.application` (see `kotlin/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
