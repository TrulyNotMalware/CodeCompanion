<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-01 | Updated: 2026-10-03 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/agent

## Purpose
H2 spec for the agent session lane in main `repository/agent/`.

## Key Files
| File | Description |
|------|-------------|
| `AgentSessionRepositoryImplTest.kt` | `@DataJpaTest` on H2 `MODE=MariaDB` (`Replace.NONE`, own in-memory DB) for the native `ON DUPLICATE KEY UPDATE`. Saving a second provider session id for the same thread key replaces it in place: one row, `created_at` from the first call's `now`, `updated_at` from the second. Snapshot isolation (MockK `JpaAgentSessionRepository` inside a `TransactionTemplate` over `SnapshotIsolationTransactionManager`): the save is a single `upsertProviderSessionId` with no `findBySessionKey` — fails with the translated 1020 when the impl reads before writing. |

## For AI Agents

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.agent.*'
```
The case runs inside the `then` leaf, so `@DataJpaTest` rolls it back and the shared H2 keeps no rows.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
