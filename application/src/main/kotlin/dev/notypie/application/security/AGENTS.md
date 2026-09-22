<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-09-22 -->

# application/security

## Purpose
Everything that decides whether a request is allowed to reach a handler. Two independent gates:

1. **Slack inbound** — HMAC signature verification plus retry de-duplication, applied by a servlet
   filter to the Slack request paths.
2. **MCP inbound** — scoped turn tokens minted for one AI turn, verified by a filter in front of the
   `/mcp` endpoint before any MCP protocol handling.

## Key Files
| File | Description |
|------|-------------|
| `SlackRequestVerificationFilter.kt` | `OncePerRequestFilter` at `HIGHEST_PRECEDENCE + 10`; runs only for Slack paths, no-ops (with a one-shot warning) when the signing secret is blank |
| `SlackSignatureVerifier.kt` | The `v0=` HMAC-SHA256 computation over `v0:timestamp:body` and its timestamp freshness window |
| `SlackRetryDeduplicator.kt` | `SlackRequestFingerprint.of(method, requestUri, body)` (SHA-256 of the raw body — never the timestamp/signature, which Slack recomputes per retry) + `InMemorySlackRetryDeduplicator(clock, ttl = 10 min, maxEntries = 10_000)`; `isDuplicateRetry` records first attempts and flags a repeat only when `X-Slack-Retry-Num` is present, `markFailed` forgets an attempt so its retry is processed |
| `CachedBodyHttpServletRequest.kt` | Wraps the request so the raw body can be read for signing *and* re-read by the controller; also rebuilds the form parameter map |
| `SlackRequestVerificationConfiguration.kt` | Filter/bean registration for the Slack gate |
| `mcp/ScopedTurnToken.kt` | Token claims: subject user, scope key, turn id, issued/expiry |
| `mcp/ScopedTurnTokenCodec.kt` | Mint/verify `v1.<b64url(payload)>.<b64url(hmac-sha256)>` |
| `mcp/McpTurnTokenFilter.kt` | Rejects unauthenticated MCP requests before any protocol handling; loopback-only unless `allowRemote` |

## Subdirectories
| Directory | Purpose |
|-----------|---------|
| `mcp/` | Authentication for the MCP endpoint (see `mcp/AGENTS.md`) |

## For AI Agents

### Working In This Directory
- **Body caching is load-bearing.** Slack signatures are computed over the exact raw body. Anything that
  consumes the input stream before the filter, or that re-encodes the body, breaks verification. Use
  `CachedBodyHttpServletRequest`; do not add another wrapper.
- **A blank signing secret disables verification on purpose** (local/dev convenience) and logs one
  warning. Do not turn that into a hard failure without checking the local and test profiles.
- **Dedup semantics:** a fingerprint seen before is only treated as a duplicate when
  `X-Slack-Retry-Num` is present. A legitimate identical-looking first delivery is not dropped. The filter
  calls `markFailed` when the chain throws or leaves a 5xx, so Slack's retry after a failure is processed
  rather than acknowledged away. The store is in-memory with a TTL and a size cap — it is per-instance, so
  it de-duplicates Slack's own retries, not cross-instance replays. Before 2026-09-22 the key included the
  timestamp and signature, which made the deduplicator dead code (every retry has fresh values).
- **MCP filter rejects before `initialize` and `tools/list`**, so no anonymous request ever reaches the
  MCP server. It is registered through a `FilterRegistrationBean` scoped to the endpoint path and only
  when MCP is enabled. Keep it ahead of protocol handling.
- **The token is not the authority on role.** `McpToolGate` re-resolves the caller's role from DB/config
  on every tool call so a mid-conversation revoke takes effect immediately. Never move role decisions
  into the token claims.
- Token format is wire-compat checked by `scripts/mcp-smoke.sh`, which mints a token exactly the way
  `ScopedTurnTokenCodec` does. Change the format and that script must change with it.

### Testing Requirements
```bash
./gradlew :application:test --tests '*Slack*Verif*' --tests '*Retry*' --tests '*ScopedTurnToken*'
```
Specs: `SlackSignatureVerifierTest`, `SlackRequestVerificationFilterTest`, `SlackRetryDeduplicatorTest`,
`CachedBodyHttpServletRequestTest`, `mcp/ScopedTurnTokenCodecTest`. Deduplicator and codec specs pin a
`Clock` — do so for any new time-bounded behaviour (freshness window, TTL, token expiry) and add a
negative case (expired / tampered signature), not just the happy path.
End-to-end MCP auth can be probed against a running app with `./scripts/mcp-smoke.sh`.

### Common Patterns
- `OncePerRequestFilter` with `shouldNotFilter` narrowing by path prefix, rather than a broad filter
  that inspects everything.
- Constructor-injected `Clock` for every expiry/TTL decision. The context's single `Clock` bean
  (`slackRequestVerificationClock`) is `Clock.systemDefaultZone()` on purpose: it is injected into every bean
  that declares a `clock: Clock` parameter (a Kotlin default never applies when a bean exists), and the
  outbox schedulers / health probe compare its `LocalDateTime` against DB timestamps written in the JVM
  zone. Signature verification only reads epoch seconds, so the zone is irrelevant to it.
- Interface + in-memory implementation (`SlackRetryDeduplicator` / `InMemorySlackRetryDeduplicator`)
  so a distributed implementation can be swapped in without touching the filter.
- Fail closed and log the reason; never leak the expected signature or token into a response.

## Dependencies

### Internal
- `application/configurations/AppConfig` — `api.signingSecret`, MCP enablement and remote-access flags
- `application/service/command/CommandRoleResolver` — via `McpToolGate`, for live role resolution

### External
Jakarta Servlet API, Spring Web filters, kotlin-logging, JDK crypto (`javax.crypto` HMAC).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
