<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-30 | Updated: 2026-08-30 -->

# application/src/testFixtures/kotlin/dev/notypie/application/security/mcp

## Purpose
Builder for `ScopedTurnToken`, the per-turn credential the MCP tool gate checks before a domain read tool
runs. Lives in the token's own package so it calls the constructor without reaching across packages.

## Key Files
| File | Description |
|------|-------------|
| `ScopedTurnTokenCreator.kt` | `createScopedTurnToken(userId = "U_REQUESTER", sessionKey = "C012ABCDEFG:1751.0001", turnId = <fixed UUID literal>, expiresAt = 2026-07-08T12:05:00Z)` |

## For AI Agents

### Working In This Directory
- Consumers: `application/src/test/.../mcp/McpToolGateTest.kt` and `mcp/DomainReadToolsTest.kt` (the specs
  sit under `mcp/`, the fixture under `security/mcp/`, each matching its type's main package).
- `expiresAt` is fixed on purpose; expiry specs pair it with a `Clock.fixed` just before or after
  2026-07-08T12:05:00Z rather than mutating the token.
- `sessionKey` follows the `<channelId>:<threadTs>` shape `ScopedTurnTokenCodec` expects — keep that format
  when overriding, or round-trip specs fail for the wrong reason.
- `turnId` defaults to a literal UUID string, not `UUID.randomUUID()`, so two tokens built with defaults are
  equal — override it when a spec needs distinct turns.

### Testing Requirements
No spec for the fixture itself; `McpToolGateTest` and `DomainReadToolsTest` are the coverage.

### Common Patterns
- Expression-bodied `create*` with every parameter defaulted and named at the call site.

## Dependencies

### Internal
- `dev.notypie.application.security.mcp.ScopedTurnToken` (main)

### External
- `java.time.Instant`

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
