<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# test/kotlin/dev/notypie/application/security/mcp

## Purpose
Spec for `ScopedTurnTokenCodec`, the HMAC-signed `v1.<payload>.<signature>` token that `AgentConverseService`
mints per AI turn and the MCP server verifies to learn which Slack user, session, and turn a tool call belongs to.

## Key Files
| File | Description |
|------|-------------|
| `ScopedTurnTokenCodecTest.kt` | `ScopedTurnTokenCodec(signingSecret, tokenTtl = 300 s, clockSkew = 30 s, clock)`. `mint` → `verify` round-trips `userId`, `sessionKey`, `turnId`, `expiresAt = mintedAt + 300 s`; verify at +329 s passes (inside skew), +331 s → null; payload segment spliced from another token → null; last signature char flipped → null; different secret → null; malformed (`"garbage"`, `v2.` prefix, two segments, non-base64 payload, empty) → null without throwing; blank secret → `mint` throws `IllegalStateException`, `verify` returns null (fails closed). |

## For AI Agents

### Working In This Directory
- Expiry is `mintedAt + ttl + skew` evaluated on the verifying codec's clock; the local `codecAt(instant,
  signingSecret)` helper exists so every `verify` runs on a fresh codec pinned at the target instant.
- The splice case relies on `token.split(".")` giving exactly three segments; a format change must update
  both the codec and that case.
- `verify` returns `null` for every rejection — never an exception. Keep that contract: the MCP gate treats
  null as "Unauthenticated" and does not catch.
- `createScopedTurnToken()` in `testFixtures/.../security/mcp/` builds the *decoded* object for the `mcp/`
  specs; this spec deliberately mints real tokens instead.

### Testing Requirements
```bash
./gradlew :application:test --tests 'dev.notypie.application.security.mcp.*'
```
No `testFixtures` builders are used.

### Common Patterns
- Tampering cases derive the bad token from a good one (`dropLast(1)`, segment swap) so the assertion is about
  the signature, not about token syntax.

## Dependencies

### Internal
- `application/security/mcp/ScopedTurnTokenCodec.kt`, `ScopedTurnToken`

### External
Kotest only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
