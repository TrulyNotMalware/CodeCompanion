<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-01 -->

# application/security/mcp

## Purpose
Authentication for the MCP endpoint. `AgentConverseService` mints one short-lived, HMAC-signed
`ScopedTurnToken` per AI turn and hands it to the sidecar; the sidecar sends it back as
`Authorization: Bearer` on every MCP request. `McpTurnTokenFilter` rejects anything without a valid
token before the MCP protocol sees the request, and the transport provider's context extractor (in
`configurations/McpServerConfiguration`) re-verifies it and places the decoded token into the
`McpTransportContext` that `mcp/McpToolGate` reads.

## Key Files
| File | Description |
|------|-------------|
| `ScopedTurnToken.kt` | `const val SCOPED_TURN_TOKEN_CONTEXT_KEY = "dev.notypie.mcp.scoped-turn-token"` and `data class ScopedTurnToken(userId, sessionKey, turnId, expiresAt: Instant)`. Deliberately carries no role |
| `ScopedTurnTokenCodec.kt` | `class ScopedTurnTokenCodec(signingSecret, tokenTtl, clockSkew, clock = Clock.systemUTC())`. `mint(userId, sessionKey, turnId)` → `v1.<b64url(json{sub,sk,tid,iat,exp})>.<b64url(HmacSHA256)>` (throws on a blank secret). `verify(token): ScopedTurnToken?` returns null on blank secret, wrong segment count or version, signature mismatch (`MessageDigest.isEqual`, constant time), unparsable payload, or `now > exp + clockSkew`. Owns a vanilla Jackson 3 `JsonMapper` |
| `McpTurnTokenFilter.kt` | `class McpTurnTokenFilter(scopedTurnTokenCodec, allowRemote) : OncePerRequestFilter`. Rejects with `401` + `{"error":"unauthorized","reason":"..."}` where reason is `loopback-only` (non-loopback `remoteAddr` while `allowRemote=false`), `missing token` (no `Bearer ` header) or `invalid token` (`verify` returned null). Registered by `McpServerConfiguration.mcpTurnTokenFilterRegistration` at `HIGHEST_PRECEDENCE + 20` for `${mcpEndpoint}` and `${mcpEndpoint}/*` |

## For AI Agents

### Working In This Directory
- Two verifications per request are intentional: the filter fails closed before `initialize` /
  `tools/list`, and the `contextExtractor` in `McpServerConfiguration` decodes the token again to
  populate the context. Both call `ScopedTurnTokenCodec.verify`; change one and the other, and keep the
  `Bearer ` prefix handling identical.
- Not a JWT on purpose. Fixed algorithm, fixed `v1` version, constant-time compare — do not swap in a
  JWT library and inherit its alg-confusion surface. `scripts/mcp-smoke.sh` mints tokens in exactly this
  format, so a format change must update that script.
- Minting sites: `service/agent/AgentConverseService` (`turnId = event.idempotencyKey`) is the only
  production caller; the codec bean exists only when MCP is enabled, so the service receives it as a
  nullable and simply sends no token (and the model gets no tools) otherwise.
- `allowRemote=false` (default) means `InetAddress.getByName(remoteAddr).isLoopbackAddress` must be
  true. `remoteAddr` is the socket peer only because the base `application.yaml` pins
  `server.forward-headers-strategy: none`: Boot otherwise enables forward headers on its own in Kubernetes
  (`CloudPlatform.KUBERNETES`), Jetty replaces `remoteAddr` with `X-Forwarded-For`, and any pod could pass
  the check by sending `X-Forwarded-For: 127.0.0.1` (before 2026-09-30 that was the case). Behind a reverse
  proxy or a mesh sidecar `remoteAddr` is the proxy (Istio's inbound redirect arrives from `127.0.0.6`, which
  *is* loopback), so remote sidecars need `slack.app.mcp.allow-remote=true` plus a network policy — the token,
  not the address, is the perimeter.
- Expiry uses `exp + clockSkew` on the verifier's clock only; `iat` is informational. TTL and skew come
  from `slack.app.mcp.token-ttl-seconds` / `clock-skew-seconds`, the secret from
  `slack.app.mcp.signing-secret` (boot fails when MCP is enabled with a blank secret).
- Role decisions never belong here — `mcp/McpToolGate` resolves the role from DB/config per call. A token
  outlives a revoke, but the next call is denied on every replica: the resolver never caches an elevated
  role (only `USER`).

### Testing Requirements
```bash
./gradlew :application:test --tests '*ScopedTurnTokenCodecTest*'
```
`ScopedTurnTokenCodecTest` (Kotest `BehaviorSpec`) pins a fixed `Clock` and covers round-trip, expiry
inside/outside the skew window, tampered signature, wrong version, and blank secret.
`McpTurnTokenFilterTest` drives the filter with `MockHttpServletRequest` (a pod address is rejected
`loopback-only`; loopback + valid token reaches the chain), serves it behind a real Jetty connector to show that
under the prod configuration a client-supplied `X-Forwarded-For` never reaches `remoteAddr` (with forward
headers on, the same request is rejected — the control), and runs
Boot's `JettyWebServerFactoryCustomizer` with `spring.main.cloud-platform=kubernetes` over the real
`application*.yaml` files to assert forward headers stay off. Build token claims with
`createScopedTurnToken()` from `src/testFixtures/kotlin/dev/notypie/application/security/mcp/ScopedTurnTokenCreator.kt`.

### Common Patterns
- Constructor-injected `Clock` (`Clock.systemUTC()` default) for every time decision.
- `verify` returns `null` rather than throwing; callers treat null as "reject" and never log the token.
- `OncePerRequestFilter` scoped by `FilterRegistrationBean.urlPatterns`, not by `shouldNotFilter`.

## Dependencies

### Internal
- `application/configurations/McpServerConfiguration` — codec bean, filter registration, context extractor
- `application/configurations/AppConfig.Mcp` — secret, TTL, skew, `allowRemote`
- `application/service/agent/AgentConverseService` — mints the token per turn
- `application/mcp/McpToolGate` — consumes the token from the transport context

### External
Jakarta Servlet, Spring Web (`OncePerRequestFilter`, `MediaType`), Jackson 3 `JsonMapper`, JDK
`javax.crypto.Mac`, `MessageDigest`, `Base64`.

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
