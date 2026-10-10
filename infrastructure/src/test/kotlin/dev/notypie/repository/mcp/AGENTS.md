<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-10-07 | Updated: 2026-10-07 -->

# infrastructure/src/test/kotlin/dev/notypie/repository/mcp

## Purpose
H2 spec for the MCP tool-call audit lane in main `repository/mcp/`.

## Key Files
| File | Description |
|------|-------------|
| `McpToolCallHistoryRepositoryImplTest.kt` | `@DataJpaTest` on its own H2 `MODE=MariaDB` DB (`Replace.NONE`). Rows come from `createMcpToolCallHistorySchema` and are aged with a JDBC `UPDATE … SET created_at` after `persistAndFlush`: `list_meetings` `COMPLETED` twice (one exactly at `since`), `get_status` `COMPLETED` and `DENIED` inside the window, `get_status` `FAILED` a minute before it. `countByToolSince` → `get_status`/`COMPLETED` 1, `get_status`/`DENIED` 1, `list_meetings`/`COMPLETED` 2 in that order, without the old `FAILED`; a window after every row → empty. |

## For AI Agents

### Testing Requirements
```bash
./gradlew :infrastructure:test --tests 'dev.notypie.repository.mcp.*'
```
Every case seeds its rows inside the `then` leaf, so `@DataJpaTest` rolls them back and the H2 keeps no rows between cases.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
