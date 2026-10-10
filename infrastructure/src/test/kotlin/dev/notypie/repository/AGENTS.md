<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-08 -->

# infrastructure/src/test/kotlin/dev/notypie/repository

## Purpose
Specs for the persistence lanes in main `repository/`. Each covered lane follows the
pairing convention: `Jpa*RepositoryTest` is a `@DataJpaTest` on the shared H2 that exercises real JPQL and
native CAS statements, and `*RepositoryImplTest` is a plain Kotest spec with a MockK'd JPA repository that
pins the mapping/delegation layer. Coverage gaps as of this writing: `standup/`, `agent/`, `mcp/` and `authorization/` have
only the H2 specs listed below, and `JpaCveSubscriptionRepository` has no H2 spec. `outbox/` covers the codec,
the row builder and, in `MessageOutboxRepositoryTest`, every native claim / renew / defer / complete /
abandon / purge statement of `MessageOutboxRepository` on H2.

## Key Files
| File | Description |
|------|-------------|
| `NativeQueryStatusLiteralTest.kt` | Plain `StringSpec` guard: every `'QUOTED_CONSTANT'` in a native `@Query` of the seven status-CAS repositories must be a constant of that table's status enum (`MessageStatus`, `CveSummaryStatus`, `CveDeliveryStatus`, `MeetingReminderStatus`, `DispatchStatus`, `SessionStatus`, `CalendarSyncStatus`), and a classpath scan of `dev.notypie.repository` fails if another repository starts quoting constants without a mapping. Renaming a constant still needs a data migration; this only stops the SQL from silently matching nothing |
| `SchemaNullabilityTest.kt` | `@DataJpaTest` `StringSpec` reading H2's `INFORMATION_SCHEMA.COLUMNS`: `outbox_message.status` and `meeting_participants.absent_reason`, both non-null Kotlin properties, are `NOT NULL` in the schema Hibernate generates. It sees only the generated schema; an existing MariaDB column keeps whatever nullability it was created with |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `agent/` | `AgentSessionRepositoryImplTest` — the provider-session upsert on H2 MariaDB mode and its no-read-before-write shape; `AgentTurnHistoryRepositoryImplTest` — the windowed usage aggregates (see `agent/AGENTS.md`) |
| `authorization/` | `UserCommandRoleRepositoryImplTest` — changing a user's grant in place (see `authorization/AGENTS.md`) |
| `cve/` | Four `Jpa*` H2 specs + five `*Impl` delegation specs for topics, events, subscriptions, deliveries, collect ledger (see `cve/AGENTS.md`) |
| `mcp/` | `McpToolCallHistoryRepositoryImplTest` — tool-call counts per tool and outcome since a window start (see `mcp/AGENTS.md`) |
| `meeting/` | `JpaMeetingRepositoryTest`, `MeetingRepositoryImplTest`, and the Spring-booting `MeetingRepositoryWriteTest` (see `meeting/AGENTS.md`) |
| `outbox/` | `MessageOutboxRepositoryTest` (H2 native statements), `OutboundMessageCodecTest`; `schema/` holds `OutboxMessageTest` (see `outbox/AGENTS.md`) |
| `standup/` | `JpaStandupSessionRepositoryTest` (both collections map each answer once) and `StandupDispatchSweepTest` (clock-bound stuck sweep) (see `standup/AGENTS.md`) |
| `calendar/` | `JpaGoogleOAuthStateRepositoryTest` (single-use consume CAS, per-user delete, purge), `GoogleCalendarConnectionRepositoryImplTest` (upsert in place, delete, active-connection check, `markRevoked`) and `JpaMeetingCalendarEventRepositoryTest` (mirror queue on its own `MODE=MariaDB` H2 database: enqueue upsert/touch, claim snapshot, generation CAS on success and failure, delete/release, retry/fail, stuck sweep, per-user fail and the id-bounded revive (`touchForUser`), worker transaction guard, sync view) (see `calendar/AGENTS.md`) |

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
