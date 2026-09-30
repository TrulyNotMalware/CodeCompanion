<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-09-30 -->

# test/kotlin/dev/notypie/application/security/mcp

## Purpose
Specs for `ScopedTurnTokenCodec`, the HMAC-signed `v1.<payload>.<signature>` token that `AgentConverseService`
mints per AI turn and the MCP server verifies to learn which Slack user, session, and turn a tool call belongs to,
and for `McpTurnTokenFilter`'s loopback check together with the forward-header setting it depends on.

## Key Files
| File | Description |
|------|-------------|
| `ScopedTurnTokenCodecTest.kt` | `ScopedTurnTokenCodec(signingSecret, tokenTtl = 300 s, clockSkew = 30 s, clock)`. `mint` → `verify` round-trips `userId`, `sessionKey`, `turnId`, `expiresAt = mintedAt + 300 s`; verify at +329 s passes (inside skew), +331 s → null; payload segment spliced from another token → null; last signature char flipped → null; different secret → null; malformed (`"garbage"`, `v2.` prefix, two segments, non-base64 payload, empty) → null without throwing; blank secret → `mint` throws `IllegalStateException`, `verify` returns null (fails closed). |
| `McpTurnTokenFilterTest.kt` | `McpTurnTokenFilter(codec, allowRemote = false)` on `MockHttpServletRequest`: a pod address (`10.244.1.7`) with `X-Forwarded-For: 127.0.0.1` and a valid token → 401 `loopback-only`, chain not invoked; `127.0.0.1` + valid token → chain invoked. Then Boot's `JettyWebServerFactoryCustomizer` runs with `spring.main.cloud-platform=kubernetes` over the real `application.yaml` (alone, and under `application-prod.yaml` / `application-dev.yaml`) bound into `ServerProperties`: forward headers stay off; with no strategy configured they turn on (the T23 mechanism). |

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
No `testFixtures` builders are used. `McpTurnTokenFilterTest` reads the main `application*.yaml` from the test
classpath, so a profile that re-enables forward headers fails it.

### Common Patterns
- Tampering cases derive the bad token from a good one (`dropLast(1)`, segment swap) so the assertion is about
  the signature, not about token syntax.

## Dependencies

### Internal
- `application/security/mcp/ScopedTurnTokenCodec.kt`, `ScopedTurnToken`

### External
Kotest only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
