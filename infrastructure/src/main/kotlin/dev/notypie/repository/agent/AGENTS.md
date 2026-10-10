<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-07 -->

# infrastructure/repository/agent

## Purpose
Persistence for the AI-agent lane: `agent_session` maps a Slack conversation key to the sidecar's own
session id so a thread resumes as one conversation, and `agent_turn_history` is the append-only audit of
every turn (outcome, error code, token usage, duration), read back as windowed aggregates for `@bot usage`.

## Key Files
| File | Description |
|------|-------------|
| `AgentSessionRepository.kt` | Port: `findProviderSessionId(sessionKey): String?`, `saveProviderSessionId(sessionKey, providerSessionId, now: LocalDateTime)` (`now` is the caller's app clock) |
| `AgentSessionRepositoryImpl.kt` | `saveProviderSessionId` is one statement, `JpaAgentSessionRepository.upsertProviderSessionId` — no read before the write. Final class, no `@Transactional` |
| `JpaAgentSessionRepository.kt` | `JpaRepository<AgentSessionSchema, Long>` + derived `findBySessionKey(sessionKey): AgentSessionSchema?`; native `upsertProviderSessionId(sessionKey, providerSessionId, now)` = `INSERT … ON DUPLICATE KEY UPDATE provider_session_id, updated_at` on `uk_agent_session_session_key` (`created_at` and `updated_at` from `:now`) |
| `AgentTurnHistoryRepository.kt` | `data class AgentTurnRecord(sessionKey, requesterId, channel, idempotencyKey: UUID, outcome: AgentTurnOutcome, errorCode?, inputTokens?, outputTokens?, durationMs)`; aggregate rows `AgentTurnOutcomeUsage(outcome, turns, inputTokens, outputTokens, totalDurationMs)` and `RequesterTurnUsage(requesterId, turns, inputTokens, outputTokens)` (all `Long`); port `record(turn)`, `countByOutcomeSince(since)`, `topRequestersSince(since, limit)` |
| `AgentTurnHistoryRepositoryImpl.kt` | Maps `AgentTurnRecord` to `AgentTurnHistorySchema` (UUID → 36-char string) and saves; the two aggregates delegate to the JPQL below (`limit` → `PageRequest.of(0, limit)`) |
| `JpaAgentTurnHistoryRepository.kt` | `JpaRepository<AgentTurnHistorySchema, Long>` + two JPQL constructor-expression queries over `created_at >= :since`: `countByOutcomeSince` (`GROUP BY outcome`) and `topRequestersSince(since, pageable)` (`GROUP BY requesterId ORDER BY COUNT DESC, requesterId ASC`). Token and duration sums are `COALESCE(SUM(...), 0L)`, so a group whose turns all lack token counts reads 0, not null |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `schema/` | `AgentSessionSchema`, `AgentTurnHistorySchema`, `AgentTurnOutcome` (see `schema/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **`sessionKey` is unique at the DB level, and `saveProviderSessionId` must stay a single upsert that is
  the first statement of `AgentConverseService`'s answer transaction** (2026-10-03). The answer's outbox rows
  share that transaction. On production MariaDB (REPEATABLE READ, `innodb_snapshot_isolation` ON), a
  `findBySessionKey` before the write turned an update by another turn of the same thread into ER_CHECKREAD
  1020 at commit and rolled the answer back; an upsert after any plain read in the transaction fails the same
  way (both reproduced on MariaDB 12.3.3). The old find-then-insert also lost a 1062 race on a brand-new key.
- **These `*Impl`s are not `open` and carry no `@Transactional`**, unlike every other lane; each
  `save` runs in Spring Data's own transaction. Follow the `open class` + `@Transactional` shape from
  `repository/cve` if a method ever needs two statements to commit together.
- **`record` is fire-and-forget audit;** a failure here should not fail the user's turn — the caller
  (`AgentConverseService`) decides. Rows are never updated.
- `turnId` in `mcp_tool_call_history` joins `agent_turn_history.idempotency_key`; keep the 36-char string
  form when touching either side. Migrations: `V9` (`agent_session`), `V10` (`agent_turn_history`).
- Beans: `JpaConfiguration.agentSessionRepository` / `agentTurnHistoryRepository`; consumers:
  `:application` `AgentConverseService` (writes) and `AgentUsageReportService` (aggregates).
- `created_at` is `@CreationTimestamp` (JVM clock, system zone), so the `since` a caller passes must come from a
  system-zone clock too; the app's `Clock` bean is `Clock.systemDefaultZone()`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.*'
```
`AgentSessionRepositoryImplTest` (`@DataJpaTest` on H2 `MODE=MariaDB` for the upsert) plus `AgentConverseService`
specs in `:application` that mock the two ports. `AgentTurnHistoryRepositoryImplTest` (same H2 mode) pins the two
aggregates; `record` has no spec here.

### Common Patterns
- Port interface + `*Impl` + `Jpa*Repository` triple; ports expose primitives / records, never entities.
- Named arguments on every repository call.

## Dependencies

### Internal
- `repository/agent/schema`

### External
Spring Data JPA.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
