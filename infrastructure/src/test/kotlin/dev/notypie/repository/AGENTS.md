<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-02 -->

# infrastructure/src/test/kotlin/dev/notypie/repository

## Purpose
Specs for the persistence lanes in main `repository/`. Each covered lane follows the
pairing convention: `Jpa*RepositoryTest` is a `@DataJpaTest` on the shared H2 that exercises real JPQL and
native CAS statements, and `*RepositoryImplTest` is a plain Kotest spec with a MockK'd JPA repository that
pins the mapping/delegation layer. Coverage gaps as of this writing: `mcp/` has no spec here; `standup/`, `agent/` and `authorization/` have
only the H2 specs listed below, and `JpaCveSubscriptionRepository` has no H2 spec. `outbox/` covers the codec,
the row builder and, in `MessageOutboxRepositoryTest`, every native claim / renew / defer / complete /
abandon / purge statement of `MessageOutboxRepository` on H2.

## Key Files
| File | Description |
|------|-------------|
| `SchemaNullabilityTest.kt` | `@DataJpaTest` `StringSpec` reading H2's `INFORMATION_SCHEMA.COLUMNS`: `outbox_message.status` and `meeting_participants.absent_reason`, both non-null Kotlin properties, are `NOT NULL` in the schema Hibernate generates. It sees only the generated schema; an existing MariaDB column keeps whatever nullability it was created with |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `agent/` | `AgentSessionRepositoryImplTest` — replacing a thread's provider session id (see `agent/AGENTS.md`) |
| `authorization/` | `UserCommandRoleRepositoryImplTest` — changing a user's grant in place (see `authorization/AGENTS.md`) |
| `cve/` | Four `Jpa*` H2 specs + five `*Impl` delegation specs for topics, events, subscriptions, deliveries, collect ledger (see `cve/AGENTS.md`) |
| `meeting/` | `JpaMeetingRepositoryTest`, `MeetingRepositoryImplTest`, and the Spring-booting `MeetingRepositoryWriteTest` (see `meeting/AGENTS.md`) |
| `outbox/` | `MessageOutboxRepositoryTest` (H2 native statements), `OutboundMessageCodecTest`; `schema/` holds `OutboxMessageTest` (see `outbox/AGENTS.md`) |
| `standup/` | `JpaStandupSessionRepositoryTest` (both collections map each answer once) and `StandupDispatchSweepTest` (clock-bound stuck sweep) (see `standup/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
