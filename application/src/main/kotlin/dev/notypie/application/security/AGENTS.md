<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-25 | Updated: 2026-10-02 -->

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
| `SlackRequestVerificationFilter.kt` | `OncePerRequestFilter` at `HIGHEST_PRECEDENCE + 10`. Constructor takes `AppConfig`, `Environment`, verifier, deduplicator, `MeterRegistry` and runs `requireUsableSigningSecret` (startup fails on an unresolved `${...}` placeholder in any profile, or on a blank secret unless `local` is the only active profile; the message names the active profiles, never the secret). Applies to every path under `/api/slack` and `/api/slash`, judged on the raw URI, the container-decoded `servletPath + pathInfo`, and Spring's per-segment-decoded path (path parameters stripped), each normalised (`..`/`.`, repeated slashes, trailing slash, lowercase). Order: blank secret (`local` alone) → pass through with a one-shot warning; `checkHeaders` fails (timestamp missing, not an integer or outside the tolerance; signature not `v0=` + 64 lower-case hex) → 401 without reading the body; body over 1 MiB → 413; bad signature → 401; non-events paths → chain; `/api/slack/events` → deduplicator admission. A retry answered 503 increments the `codecompanion.slack.retry.deferred` counter (`METRIC_DEFERRED_RETRIES`). `SlackHeaders` also carries `X-Slack-No-Retry` |
| `SlackSignatureVerifier.kt` | `checkHeaders(timestamp, signature, tolerance)` — the body-free part (presence, freshness window, `v0=[0-9a-f]{64}` shape → `MALFORMED_SIGNATURE`) that the filter runs before buffering; `verify(...)` = `checkHeaders` + the `v0=` HMAC-SHA256 over `v0:timestamp:body`; hex via the stdlib `ByteArray.toHexString()` (the only hex helper in the package) |
| `SlackRetryDeduplicator.kt` | `SlackRequestFingerprint.of(method, requestPath, body)` (normalised path + SHA-256 of the raw body — never the timestamp/signature, which Slack recomputes per retry); sealed `SlackRetryAdmission` (`FirstAttempt(ticket)`, `Untracked`, `RetryOfInFlight`, `RetryOfCompleted`); `SlackRetryTicket(fingerprint, generation)`; `InMemorySlackRetryDeduplicator(clock, ttl = 10 min, maxEntries = 10_000)` with `admit` / `markCompleted(ticket)` / `markFailed(ticket)` over an IN_FLIGHT / COMPLETED map whose entries carry the generation of the admission that created them |
| `CachedBodyHttpServletRequest.kt` | Wraps the request so the raw body can be read for signing *and* re-read by the controller; rebuilds the parameter map from the signed form body only — the query string is ignored by `getParameter` / `getParameterValues` / `getParameterMap` / `getParameterNames` and `getQueryString()` returns `null`, because Slack's signature does not cover the URL. Built only through `cacheWithinLimit(request)`, which returns `null` when `contentLengthLong` exceeds `MAX_BODY_BYTES` (1 MiB) or a bounded `readNBytes(MAX_BODY_BYTES + 1)` overflows (chunked bodies) |
| `SlackRequestVerificationConfiguration.kt` | `Clock`, `SlackSignatureVerifier` and `SlackRetryDeduplicator` beans for the Slack gate (the filter itself is a `@Component`) |
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
- **Path matching is default-deny.** Every path that resolves under `/api/slack` or `/api/slash` by any of
  the three views (raw URI, container-decoded path, Spring's decoded segments) is verified. Before
  2026-09-28 the filter compared the raw `requestURI` with `startsWith`, so `/api/sla%63k/events`,
  `/api/x/../slack/events` or `/api/slack/./events` reached the controller unsigned on Jetty 12.1. Do not
  mount unsigned endpoints (actuator, probes) under those prefixes, and do not go back to raw-URI matching.
- **Only REQUEST dispatches are verified.** `OncePerRequestFilter` skips ERROR and ASYNC dispatches by default,
  which is safe only because nothing re-dispatches to a Slack controller: the controllers are synchronous, the
  error path is Boot's `/error`, and there is no `forward:` or `RequestDispatcher` in main sources. Adding an
  error page, async handler or forward that reaches `/api/slack/**` or `/api/slash/**` would bypass
  verification — override `shouldNotFilterErrorDispatch` / `shouldNotFilterAsyncDispatch` first.
- **Signing secret policy.** An unresolved placeholder (Boot keeps `${SLACK_SIGNING_SECRET}` as a literal when
  the variable is missing) fails startup in every profile; a blank secret fails startup unless `local` is the
  **only** active profile (Socket Mode, no inbound HTTP from Slack). `local` combined with anything else
  (`slack-live,local`, `prod,local`) fails, because `local`'s empty `${SLACK_SIGNING_SECRET:}` default would
  otherwise switch verification off on a network-exposed instance. Only `local` alone disables verification,
  with one warning.
- **Cheap rejection first — against scanners, not against an attacker.** Header presence, timestamp freshness
  and signature shape are checked before the body is read, so requests without plausible Slack headers cost no
  buffering. The check is not authentication: the format is public (current epoch seconds + `v0=` + any 64
  lower-case hex digits), so a forged request that matches it is buffered up to the 1 MiB limit (`readNBytes`
  can briefly hold about twice that) before the HMAC rejects it. Unauthenticated clients can therefore force
  roughly 1–2 MiB of heap per concurrent request, and Jetty's virtual-thread pool is the only concurrency cap in
  the app. Put a per-client rate limit and a request-body / concurrency limit on the gateway (ingress /
  HTTPRoute) in front of `/api/slack` and `/api/slash`; do not rely on this pre-check for flood protection.
- **Parameters come from the signed body only.** Never re-enable query-string parameters in the wrapper: a
  replayed signed request with `?payload=` / `?user_id=` appended would otherwise override the signed values.
- **Body limit.** 1 MiB, enforced before signature verification because the body is buffered to verify it.
  Do not lower it without a Slack-documented maximum for Events API / interaction payloads to size it against:
  a large `view_submission` with non-ASCII text inflates several-fold under form encoding.
- **Dedup state machine (Events API only).** Only `/api/slack/events` participates — Slack sends
  `X-Slack-Retry-Num` only for Events API deliveries, so slash commands and interactions are never recorded.
  First attempt → IN_FLIGHT; chain completes below 500 → COMPLETED; 5xx or any `Throwable` → forgotten.
  A retry that hits IN_FLIGHT gets **503** (Slack keeps retrying — answering 200 there lost the event when the
  original later failed); a retry that hits COMPLETED gets 200 without dispatch; a retry with no entry is
  processed and tracked. An identical body without a retry number is judged by the entry the same way (503 while
  in flight, 200 without dispatch once completed): Slack never resends a body (its `event_id` is unique) without
  `X-Slack-Retry-Num`, so such a copy is a replay of a captured request and must not run the command or AI turn
  again. `admit` therefore no longer reads `retryNum`; the filter still logs it. Entries expire after
  the TTL (amortised sweep every TTL/2). At `maxEntries` the oldest COMPLETED entries are trimmed to 90% of
  the cap in one pass under a `tryLock`; IN_FLIGHT entries are never trimmed, but they still expire after the TTL
  like any other entry. When the map is still full (all in flight), a new fingerprint is answered `Untracked`:
  the event is processed but not recorded, so a signed event is never refused for bookkeeping and memory stays
  at the cap. `markCompleted` / `markFailed` apply only to the entry of their ticket's generation, so a late
  mark from an attempt whose entry already expired cannot complete or delete a newer attempt's entry. `codecompanion.slack.retry.deferred` counts the 503s: Slack counts them as delivery
  failures, so a rising rate means handlers are slower than Slack's retry interval.
- **What dedup does and does not guarantee.** The map is per JVM and the Deployment runs 2 replicas. It
  suppresses a retry only when that retry lands on the replica that handled the original; a retry routed to
  the other replica is processed again (possibly concurrently with the original). If the original is still
  in flight when Slack's last retry arrives and then fails, the event is lost — Slack has no further retry.
  Cross-replica exactly-once needs a shared store (e.g. a DB unique key on `event_id`); swap in a
  `SlackRetryDeduplicator` implementation for that.
- **MCP filter rejects before `initialize` and `tools/list`**, so no anonymous request ever reaches the
  MCP server. It is registered through a `FilterRegistrationBean` scoped to the endpoint path and only
  when MCP is enabled. Keep it ahead of protocol handling.
- **The token is not the authority on role.** `McpToolGate` re-resolves the caller's role through
  `CommandRoleResolver` on every tool call. The resolver caches only `USER` answers, for 60 s, and reads an
  elevated role from the DB every time, so a revoke applies to the next call on every replica (a call already
  in flight at commit time may still answer with the old role). A grant reaches a replica that still caches the
  user as `USER` within 60 s; `RoleManagementService` evicts the user on the committing replica after the
  commit. Never move role decisions into the token claims.
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
- `OncePerRequestFilter` whose `shouldNotFilter` decides on normalised, decoded paths (see above), never on
  the raw `requestURI` alone.
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
Jakarta Servlet API, Spring Web filters and `RequestPath` / `PathContainer` path parsing, Spring `Environment`
(active profiles), Micrometer `MeterRegistry`, kotlin-logging, JDK crypto (`javax.crypto` HMAC).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
