<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-01 | Updated: 2026-10-07 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/agent

## Purpose
H2 specs for the agent session and turn-history lanes in main `repository/agent/`.

## Key Files
| File | Description |
|------|-------------|
| `AgentSessionRepositoryImplTest.kt` | `@DataJpaTest` on H2 `MODE=MariaDB` (`Replace.NONE`, own in-memory DB) for the native `ON DUPLICATE KEY UPDATE`. Saving a second provider session id for the same thread key replaces it in place: one row, `created_at` from the first call's `now`, `updated_at` from the second. Snapshot isolation (MockK `JpaAgentSessionRepository` inside a `TransactionTemplate` over `SnapshotIsolationTransactionManager`): the save is a single `upsertProviderSessionId` with no `findBySessionKey` — fails with the translated 1020 when the impl reads before writing. |
| `AgentTurnHistoryRepositoryImplTest.kt` | `@DataJpaTest` on its own H2 `MODE=MariaDB` DB. Rows come from `createAgentTurnHistorySchema` and are aged with a JDBC `UPDATE … SET created_at` after `persistAndFlush`, because `@CreationTimestamp` overwrites a constructor value: two `U_ALPHA` `COMPLETED`, one `U_ALPHA` `FAILED` with null tokens, one `U_BETA` `COMPLETED` inside the window (one exactly at `since`), one `U_GAMMA` `BUSY` a minute before it. `countByOutcomeSince` → `COMPLETED` 3 turns / 1,700 in / 170 out / 3,500 ms and `FAILED` 1 / 0 / 0 / 300 ms (COALESCE keeps a null-token group at 0); `topRequestersSince(limit = 5)` → `U_ALPHA` then `U_BETA` with their sums, `limit = 1` → only `U_ALPHA`; a window after every row → both empty. |

## For AI Agents

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.agent.*'
```
Every case seeds its rows inside the `then` leaf, so `@DataJpaTest` rolls them back and the H2 keeps no rows between cases.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
