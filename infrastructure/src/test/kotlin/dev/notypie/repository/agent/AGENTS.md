<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-01 | Updated: 2026-10-01 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/agent

## Purpose
H2 spec for the agent session lane in main `repository/agent/`.

## Key Files
| File | Description |
|------|-------------|
| `AgentSessionRepositoryImplTest.kt` | `@DataJpaTest` on `AgentSessionRepositoryImpl`: saving a second provider session id for the same thread key replaces the stored id through `AgentSessionSchema.resumeAs`, leaving one row per thread |

## For AI Agents

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.agent.*'
```
The case runs inside the `then` leaf, so `@DataJpaTest` rolls it back and the shared H2 keeps no rows.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
