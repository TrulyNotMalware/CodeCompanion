<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-30 -->

# infrastructure/src/test/kotlin/dev/notypie/repository

## Purpose
Specs for the persistence lanes in main `repository/`. No files at this level. Each covered lane follows the
pairing convention: `Jpa*RepositoryTest` is a `@DataJpaTest` on the shared H2 that exercises real JPQL and
native CAS statements, and `*RepositoryImplTest` is a plain Kotest spec with a MockK'd JPA repository that
pins the mapping/delegation layer. Coverage gaps as of this writing: `standup/`, `agent/`, `authorization/`,
`mcp/` have no specs here, `outbox/` covers the codec and row builder but not `MessageOutboxRepository`'s
claim CAS, and `JpaCveSubscriptionRepository` has no H2 spec.

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `cve/` | Four `Jpa*` H2 specs + five `*Impl` delegation specs for topics, events, subscriptions, deliveries, collect ledger (see `cve/AGENTS.md`) |
| `meeting/` | `JpaMeetingRepositoryTest`, `MeetingRepositoryImplTest`, and the Spring-booting `AddParticipantRepositoryTest` (see `meeting/AGENTS.md`) |
| `outbox/` | `OutboundMessageCodecTest`; `schema/` holds `OutboxMessageTest` (see `outbox/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
