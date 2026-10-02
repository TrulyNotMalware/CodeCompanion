<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-01 -->

# test/kotlin/dev/notypie/application/security/mcp

## Purpose
Specs for `ScopedTurnTokenCodec`, the HMAC-signed `v1.<payload>.<signature>` token that `AgentConverseService`
mints per AI turn and the MCP server verifies to learn which Slack user, session, and turn a tool call belongs to,
and for `McpTurnTokenFilter`'s loopback check together with the forward-header setting it depends on.

## Key Files
| File | Description |
|------|-------------|
| `ScopedTurnTokenCodecTest.kt` | `ScopedTurnTokenCodec(signingSecret, tokenTtl = 300 s, clockSkew = 30 s, clock)`. `mint` → `verify` round-trips `userId`, `sessionKey`, `turnId`, `expiresAt = mintedAt + 300 s`; verify at +329 s passes (inside skew), +331 s → null; payload segment spliced from another token → null; last signature char flipped → null; different secret → null; malformed (`"garbage"`, `v2.` prefix, two segments, non-base64 payload, empty) → null without throwing; blank secret → `mint` throws `IllegalStateException`, `verify` returns null (fails closed). |
| `McpTurnTokenFilterTest.kt` | `McpTurnTokenFilter(codec, allowRemote = false)` on `MockHttpServletRequest`: a pod address (`10.244.1.7`) with a valid token → 401 `loopback-only`, chain not invoked; `127.0.0.1` + valid token → chain invoked. The `X-Forwarded-For` spoof is driven through a real Jetty connector (G11 — the filter never reads the header, so a mock request with it proved nothing): the configured factory serves the filter on an ephemeral loopback port and a loopback client sends `X-Forwarded-For: 10.244.1.7`; under `application-prod.yaml` over `application.yaml` → 200 (the filter saw the socket peer), and with no strategy (forward headers on, Jetty's `ForwardedRequestCustomizer`) → 401, the control that proves the header would reach `remoteAddr`. Then Boot's `JettyWebServerFactoryCustomizer` runs with `spring.main.cloud-platform=kubernetes` over the real `application.yaml` (alone, and under `application-prod.yaml` / `application-dev.yaml`) bound into `ServerProperties`: forward headers stay off; with no strategy configured they turn on (the T23 mechanism). |

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
classpath, so a profile that re-enables forward headers fails it. Its Jetty cases bind `127.0.0.1` on port 0 and
stop the server in `finally`; a test client is always a loopback peer, which is why the spoof is checked from the
other side (a pod address in the header must not turn a loopback peer into a rejected one).

### Common Patterns
- Tampering cases derive the bad token from a good one (`dropLast(1)`, segment swap) so the assertion is about
  the signature, not about token syntax.

## Dependencies

### Internal
- `application/security/mcp/ScopedTurnTokenCodec.kt`, `ScopedTurnToken`

### External
Kotest only.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
