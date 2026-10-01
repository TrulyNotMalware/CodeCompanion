<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-01 -->

# infrastructure/src/test/kotlin/dev/notypie/repository

## Purpose
Specs for the persistence lanes in main `repository/`. No files at this level. Each covered lane follows the
pairing convention: `Jpa*RepositoryTest` is a `@DataJpaTest` on the shared H2 that exercises real JPQL and
native CAS statements, and `*RepositoryImplTest` is a plain Kotest spec with a MockK'd JPA repository that
pins the mapping/delegation layer. Coverage gaps as of this writing: `standup/`, `agent/`, `authorization/`,
`mcp/` have no specs here, and `JpaCveSubscriptionRepository` has no H2 spec. `outbox/` covers the codec,
the row builder and, in `MessageOutboxRepositoryTest`, every native claim / renew / defer / complete /
abandon / purge statement of `MessageOutboxRepository` on H2.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `cve/` | Four `Jpa*` H2 specs + five `*Impl` delegation specs for topics, events, subscriptions, deliveries, collect ledger (see `cve/AGENTS.md`) |
| `meeting/` | `JpaMeetingRepositoryTest`, `MeetingRepositoryImplTest`, and the Spring-booting `MeetingRepositoryWriteTest` (see `meeting/AGENTS.md`) |
| `outbox/` | `MessageOutboxRepositoryTest` (H2 native statements), `OutboundMessageCodecTest`; `schema/` holds `OutboxMessageTest` (see `outbox/AGENTS.md`) |
| `standup/` | `JpaStandupSessionRepositoryTest` — sessions fetched with both collections map each answer once (see `standup/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
