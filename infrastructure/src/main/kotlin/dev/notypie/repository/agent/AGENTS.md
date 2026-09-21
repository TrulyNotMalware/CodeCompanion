<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# infrastructure/repository/agent

## Purpose
Persistence for the AI-agent lane: `agent_session` maps a Slack conversation key to the sidecar's own
session id so a thread resumes as one conversation, and `agent_turn_history` is the append-only audit of
every turn (outcome, error code, token usage, duration).

## Key Files
| File | Description |
|------|-------------|
| `AgentSessionRepository.kt` | Port: `findProviderSessionId(sessionKey): String?`, `saveProviderSessionId(sessionKey, providerSessionId)` |
| `AgentSessionRepositoryImpl.kt` | Read-modify-write on `JpaAgentSessionRepository.findBySessionKey`; inserts when absent, saves only when the id changed. Final class, no `@Transactional` |
| `JpaAgentSessionRepository.kt` | `JpaRepository<AgentSessionSchema, Long>` + derived `findBySessionKey(sessionKey): AgentSessionSchema?` |
| `AgentTurnHistoryRepository.kt` | `data class AgentTurnRecord(sessionKey, requesterId, channel, idempotencyKey: UUID, outcome: AgentTurnOutcome, errorCode?, inputTokens?, outputTokens?, durationMs)`; port `record(turn)` |
| `AgentTurnHistoryRepositoryImpl.kt` | Maps `AgentTurnRecord` to `AgentTurnHistorySchema` (UUID → 36-char string) and saves |
| `JpaAgentTurnHistoryRepository.kt` | Bare `JpaRepository<AgentTurnHistorySchema, Long>` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `schema/` | `AgentSessionSchema`, `AgentTurnHistorySchema`, `AgentTurnOutcome` (see `schema/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **`sessionKey` is `"<channel>:<thread_ts>"`** and unique at the DB level. The read-then-save in
  `saveProviderSessionId` is not atomic, but concurrent turns for one key are rejected upstream by the
  sidecar's per-`sessionKey` gate, so the race cannot happen in practice — keep that invariant if you add
  a second writer.
- **These `*Impl`s are not `open` and carry no `@Transactional`**, unlike every other lane; each
  `save` runs in Spring Data's own transaction. Follow the `open class` + `@Transactional` shape from
  `repository/cve` if a method ever needs two statements to commit together.
- **`record` is fire-and-forget audit;** a failure here should not fail the user's turn — the caller
  (`AgentConverseService`) decides. Rows are never updated.
- `turnId` in `mcp_tool_call_history` joins `agent_turn_history.idempotency_key`; keep the 36-char string
  form when touching either side. Migrations: `V9` (`agent_session`), `V10` (`agent_turn_history`).
- Beans: `JpaConfiguration.agentSessionRepository` / `agentTurnHistoryRepository`; consumer:
  `:application` `AgentConverseService`.

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.*'
```
No spec targets this lane in `:infrastructure`; behaviour is covered from `:application` by
`AgentConverseService` specs that mock the two ports. A `@DataJpaTest` for `findBySessionKey` and the
"save only when changed" branch is the missing pair.

### Common Patterns
- Port interface + `*Impl` + `Jpa*Repository` triple; ports expose primitives / records, never entities.
- Named arguments on every repository call.

## Dependencies

### Internal
- `repository/agent/schema`

### External
Spring Data JPA.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
